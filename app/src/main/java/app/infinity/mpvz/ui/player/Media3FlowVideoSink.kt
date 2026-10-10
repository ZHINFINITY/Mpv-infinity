package app.infinity.mpvz.ui.player

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Size
import androidx.media3.common.util.TimestampIterator
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.VideoSink
import app.infinity.mpvz.preferences.effectiveMpvFlowMaxDimension
import app.infinity.mpvz.preferences.effectiveMpvFlowTargetFps
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/** Diagnostics emitted by the Media3-owned GPU interpolation route. */
data class Media3FlowDiagnostics(
  val enabled: Boolean = false,
  val sourceFps: Float = 0f,
  /** Successful EGL swap cadence; this is not a SurfaceFlinger scanout measurement. */
  val outputFps: Float = 0f,
  val generatedFps: Float = 0f,
  val targetFps: Int = 0,
  val generatedFrames: Long = 0L,
  val droppedFrames: Long = 0L,
  val missedOutputTicks: Long = 0L,
  val skippedFrames: Long = 0L,
  /** CPU time spent submitting motion-estimation commands, not a GPU-completion timer. */
  val motionEstimateSubmitMs: Float = 0f,
  /** Asynchronously retrieved GPU elapsed time for forward and backward motion passes. */
  val motionEstimateGpuMs: Float? = null,
  /** Null until a non-blocking GPU confidence readback is available. */
  val confidence: Float? = null,
  val motionGridWidth: Int = 0,
  val motionGridHeight: Int = 0,
  /** Reduced luma/motion dimensions; synthesized output remains source-resolution. */
  val processingWidth: Int = 0,
  val processingHeight: Int = 0,
  val state: String = "off",
  val bypassReason: String? = null,
  val gpuTimings: FlowGpuTimingStats = FlowGpuTimingStats(),
  val cpuTimings: FlowCpuTimingStats = FlowCpuTimingStats(),
  val pixelCoverage: FlowPixelCoverageStats = FlowPixelCoverageStats(),
)

private data class FlowFrameRateSurfacePolicy(
  val userPreferenceLabel: String,
  val changeStrategy: Int,
  val strategyLabel: String,
)

/**
 * A GPU-backed Media3 sink. Decoder frames arrive on a private SurfaceTexture; source images,
 * motion fields, and interpolated output remain GPU-resident. The bounded queue and cadence guard
 * always permit a real source-frame fallback rather than waiting on motion estimation.
 */
@androidx.media3.common.util.UnstableApi
class Media3FlowVideoSink(
  context: Context,
  requestedTargetFps: Int,
  private val onDiagnostics: (Media3FlowDiagnostics) -> Unit,
) : VideoSink {
  private val appContext = context.applicationContext
  private val isDebuggable =
    (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  private val logTag = "Mpv∞-Media3Flow"
  private val displayHz = displayRefreshRate(appContext)
  private val targetFps = effectiveMpvFlowTargetFps(requestedTargetFps, displayHz)
  private val maxDimension = effectiveMpvFlowMaxDimension(targetFps).coerceIn(160, 640)
  private val glThread = HandlerThread("Media3Flow-GLES").apply { start() }
  private val glHandler = Handler(glThread.looper)
  private val inputLock = Any()
  private val pendingInput = ArrayDeque<PendingInput>()
  private val expectedTexturePts = ArrayDeque<FrameToken>()
  private val sourceFrames = ArrayDeque<FrameSlot>()
  private val framePool = ArrayList<FrameSlot>(MAX_STORED_FRAMES)
  private val motionPairs = ArrayList<MotionPairSlot>(MAX_MOTION_PAIRS)
  private val ptsHistory = ArrayDeque<Long>()
  private val outputWallTimes = ArrayDeque<Long>()
  private val pendingGpuTimerQueries = ArrayDeque<PendingGpuTimerQuery>()
  private val gpuDurationSamplesNs = Array(FlowGpuStage.values().size) { ArrayDeque<Long>() }
  private val cpuTimingLock = Any()
  private val cpuDurationSamplesNs = Array(FlowCpuStage.values().size) { ArrayDeque<Long>() }
  private val coverageBuffers = ArrayList<CoverageBufferSlot>(COVERAGE_BUFFER_COUNT)
  private val coverageSamples = ArrayDeque<FlowCoverageSample>()
  private val generatedWallTimes = ArrayDeque<Long>()
  private val renderTaskPending = AtomicBoolean(false)
  private val renderRequestVersion = AtomicLong(0L)
  private val renderFrameTimeNanos = AtomicLong(NO_FRAME_TIME_NANOS)
  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var choreographer: Choreographer? = null
  private val frameCallbackScheduled = AtomicBoolean(false)
  private val displayFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
    frameCallbackScheduled.set(false)
    if (!disposed && running) {
      requestRenderAtDisplayFrame(frameTimeNanos)
      scheduleDisplayFrame()
    }
  }
  private val fallbackFrameRunnable = Runnable {
    frameCallbackScheduled.set(false)
    if (!disposed && running) {
      requestRenderAtDisplayFrame(null)
      scheduleDisplayFrame()
    }
  }
  private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(6 * 2 * Float.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
    .apply { position(0) }

  @Volatile private var currentFormat: Format? = null
  @Volatile private var videoAspect = VideoAspect.Fit
  @Volatile private var initialized = false
  @Volatile private var outputAvailable = false
  @Volatile private var storedFrameCount = 0
  @Volatile private var latestPositionUs = 0L
  @Volatile private var playbackClock = PlaybackClock(0L, 0L)
  @Volatile private var playbackSpeed = 1f
  @Volatile private var running = false
  @Volatile private var disposed = false
  @Volatile private var streamGeneration = 0L
  @Volatile private var inputInFlight = false
  @Volatile private var inputTimestampAdjustmentUs = 0L
  @Volatile private var streamStartPositionUs = C.TIME_UNSET
  @Volatile private var allowFirstFrameBeforeStarted = false
  @Volatile private var lastInputPtsUs = C.TIME_UNSET
  @Volatile private var lastRenderedPtsUs = C.TIME_UNSET

  @Volatile private var listener: VideoSink.Listener = VideoSink.Listener.NO_OP
  @Volatile private var listenerExecutor: Executor = Executor { it.run() }
  @Volatile private var metadataListener: VideoFrameMetadataListener? = null
  @Volatile private var outputResolution: Size = Size.UNKNOWN
  @Volatile private var changeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
  @Volatile private var endOfCurrentInput = false
  @Volatile private var endOfInput = false
  @Volatile private var originPtsUs = C.TIME_UNSET
  @Volatile private var lastOutputTick = -1L
  @Volatile private var firstFrameReported = false
  private var generatedFrames = 0L
  private val droppedFrames = AtomicLong(0L)
  private val missedOutputTicks = AtomicLong(0L)
  private var skippedFrames = 0L
  private var lastMotionSubmitMs = 0f
  private var lastMotionGpuMs: Float? = null
  private var gpuTimerQueryIds = IntArray(0)
  private var gpuTimerQueriesSupported = false
  private var activeGpuTimerQuery: PendingGpuTimerQuery? = null
  private var validGpuTimerResults = 0L
  private var disjointGpuTimerResults = 0L
  private var zeroGpuTimerResults = 0L
  private var skippedGpuTimerQueries = 0L
  private var coverageStatsAvailable = false
  private var coverageDispatchCount = 0L
  private var skippedCoverageSamples = 0L
  private var invalidCoverageSamples = 0L
  private var lastMetricsAtNs = 0L
  private var lastTimingLogAtNs = 0L
  private var lastFlowSummaryLogAtNs = 0L
  private var lastFlowSummarySignature: String? = null
  private var lastFrameRateRequestLogSignature: String? = null
  @Volatile private var outputFrameRateHintFps = 0f
  @Volatile private var matchContentFrameRatePreferenceLabel = "unknown"
  @Volatile private var surfaceFrameRateChangeStrategyLabel = "unknown"
  private var glProbeFramesRemaining = 8
  private var traceGlForCurrentInput = false
  private var lastSurfaceTimestampNs = C.TIME_UNSET
  private var lastFrameReleaseTimestampNs = C.TIME_UNSET
  private var eglInfoLogged = false
  private var lastState = "waiting"
  private var lastBypassReason: String? = null

  private var eglDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext = EGL14.EGL_NO_CONTEXT
  private var eglReady = false
  private var eglConfig: android.opengl.EGLConfig? = null
  private var pbufferSurface = EGL14.EGL_NO_SURFACE
  private var windowSurface = EGL14.EGL_NO_SURFACE
  @Volatile private var outputSurface: Surface? = null
  private var inputTextureId = 0
  private var inputSurfaceTexture: SurfaceTexture? = null
  private var inputSurface: Surface? = null
  private var copyProgram = 0
  private var blitProgram = 0
  private var lumaProgram = 0
  private var flowProgram = 0
  private var synthProgram = 0
  private var framebuffer = 0
  private var graphicsProgramsCompiled = false
  private var computeProgramsCompiled = false
  private var frameWidth = 0
  private var frameHeight = 0
  private var processingWidth = 0
  private var processingHeight = 0
  private var gridWidth = 0
  private var gridHeight = 0
  private var outputTexture = 0
  private var flowAvailable = false
  @Volatile private var preflightAvailable = false

  init {
    preflightAvailable = runCatching { runOnGlThreadSync { ensureEgl(preflightOnly = true) } }.getOrDefault(false)
    if (!preflightAvailable) Log.w(logTag, "GLES preflight failed; the stock Media3 renderer will be used")
  }

  fun isPreflightAvailable(): Boolean = preflightAvailable && !disposed

  private data class PendingInput(
    val ptsUs: Long,
    val generation: Long,
    val handler: VideoSink.VideoFrameHandler,
    val enqueuedAtNs: Long,
  )
  private data class FrameToken(val ptsUs: Long, val generation: Long, val releaseTimestampNs: Long)
  private data class PlaybackClock(val positionUs: Long, val elapsedRealtimeUs: Long)
  private enum class FlowGpuStage { DOWNSAMPLE, MOTION_FORWARD, MOTION_BACKWARD, CONSISTENCY_AND_WARP, PRESENTATION_DRAW }
  private enum class FlowCpuStage { INPUT_QUEUE_WAIT, FRAME_HANDLER_CALL, EGL_SWAP_WAIT }
  private data class PendingGpuTimerQuery(
    val id: Int,
    val stage: FlowGpuStage,
    val frame0PtsUs: Long,
    val frame1PtsUs: Long,
    val generation: Long,
  )

  private class CoverageBufferSlot {
    var bufferId = 0
    var fenceSync = 0L
    var groupCount = 0
    var byteSize = 0
    var expectedPixelCount = 0L
    var framePtsUs = C.TIME_UNSET
    var presented = false
  }

  private class FrameSlot {
    var colorTexture = 0
    var lumaTexture = 0
    var ptsUs = C.TIME_UNSET
    var inUse = false
  }

  private class MotionPairSlot {
    var forwardTexture = 0
    var backwardTexture = 0
    var frame0PtsUs = C.TIME_UNSET
    var frame1PtsUs = C.TIME_UNSET
    var available = false
    var motionEstimateSubmitMs = 0f
    var motionEstimateGpuMs: Float? = null
    var motionForwardGpuMs: Float? = null
    var motionBackwardGpuMs: Float? = null
  }

  override fun setListener(listener: VideoSink.Listener, executor: Executor) {
    this.listener = listener
    listenerExecutor = executor
  }

  override fun initialize(sourceFormat: Format): Boolean {
    currentFormat = sourceFormat
    val success = runOnGlThreadSync {
      if (!ensureEgl() || !makePbufferCurrent() || sourceFormat.width <= 0 || sourceFormat.height <= 0) {
        return@runOnGlThreadSync false
      }
      configureInputSurface(sourceFormat.width, sourceFormat.height)
      ensureProcessingResources(sourceFormat.width, sourceFormat.height)
      if (outputSurface?.isValid == true) createWindowSurface()
      inputSurface != null && inputSurfaceTexture != null
    }
    initialized = success
    if (success) {
      dispatchListener { it.onVideoSizeChanged(videoSize(sourceFormat)) }
      publishDiagnostics("ready", null, force = true)
    } else {
      lastBypassReason = "gles_init_failed"
      publishDiagnostics("fallback", lastBypassReason, force = true)
      Log.w(logTag, "GLES sink initialization failed")
    }
    return success
  }

  override fun isInitialized(): Boolean = initialized && !disposed

  override fun startRendering() {
    running = true
    endOfCurrentInput = false
    endOfInput = false
    glHandler.post {
      setOutputFrameRate()
      scheduleDisplayFrame()
    }
  }

  override fun stopRendering() {
    running = false
    glHandler.post {
      setOutputFrameRate()
      cancelDisplayFrame()
    }
  }

  override fun redraw() {
    if (disposed) return
    glHandler.post {
      if (makePbufferCurrent()) {
        sourceFrames.lastOrNull { it.ptsUs <= latestPositionUs }
          ?.let { drawSourceFrame(it, System.nanoTime(), it.ptsUs) }
      }
    }
  }

  override fun flush(resetPosition: Boolean) {
    val discarded = ArrayList<PendingInput>()
    endOfCurrentInput = false
    endOfInput = false
    synchronized(inputLock) {
      streamGeneration++
      while (pendingInput.isNotEmpty()) discarded += pendingInput.removeFirst()
      // Preserve a token for an already-released decoder buffer so that its late SurfaceTexture
      // callback is drained before a post-seek frame can be paired with the wrong timestamp.
      if (!inputInFlight) expectedTexturePts.clear()
      lastInputPtsUs = C.TIME_UNSET
    }
    discarded.forEach {
      runCatching { it.handler.skip() }
      droppedFrames.incrementAndGet()
      dispatchListener { it.onFrameDropped() }
    }
    glHandler.post {
      lastRenderedPtsUs = C.TIME_UNSET
      firstFrameReported = false
      if (resetPosition) {
        originPtsUs = C.TIME_UNSET
        lastOutputTick = -1L
      }
      clearSourceFrames()
      ptsHistory.clear()
      outputWallTimes.clear()
      generatedWallTimes.clear()
      endOfCurrentInput = false
      endOfInput = false
      publishDiagnostics("seek_flush", null, force = true)
    }
  }

  override fun isReady(otherwiseReady: Boolean): Boolean {
    val hasPendingInput = synchronized(inputLock) { pendingInput.isNotEmpty() || inputInFlight }
    return otherwiseReady && initialized && outputAvailable && (storedFrameCount > 0 || hasPendingInput)
  }

  override fun signalEndOfCurrentInputStream() {
    endOfCurrentInput = true
  }

  override fun signalEndOfInput() {
    endOfInput = true
    endOfCurrentInput = true
  }

  override fun isEnded(): Boolean {
    val signaled = endOfInput || endOfCurrentInput
    val inputDone = synchronized(inputLock) { pendingInput.isEmpty() && !inputInFlight }
    val finalPts = lastInputPtsUs
    if (!signaled || !inputDone) return false
    if (finalPts == C.TIME_UNSET) return true
    return lastRenderedPtsUs != C.TIME_UNSET && latestPositionUs >= finalPts && lastRenderedPtsUs >= finalPts
  }

  override fun getInputSurface(): Surface =
    checkNotNull(inputSurface) { "VideoSink must be initialized before its input surface is requested" }

  override fun setVideoFrameMetadataListener(videoFrameMetadataListener: VideoFrameMetadataListener) {
    metadataListener = videoFrameMetadataListener
  }

  override fun setPlaybackSpeed(speed: Float) {
    if (speed.isFinite() && speed > 0f) playbackSpeed = speed
  }

  fun setVideoAspect(aspect: VideoAspect) {
    videoAspect = aspect
    redraw()
  }

  override fun setVideoEffects(videoEffects: List<Effect>) {
    check(videoEffects.isEmpty()) { "Media3FlowVideoSink does not accept additional video effects" }
  }

  override fun setBufferTimestampAdjustmentUs(bufferTimestampAdjustmentUs: Long) {
    inputTimestampAdjustmentUs = bufferTimestampAdjustmentUs
  }

  override fun setOutputSurfaceInfo(outputSurface: Surface, outputResolution: Size) {
    val previousSurface = this.outputSurface
    this.outputSurface = outputSurface
    this.outputResolution = outputResolution
    outputAvailable = outputSurface.isValid
    glHandler.post {
      firstFrameReported = false
      if (previousSurface !== outputSurface) clearFrameRateHint(previousSurface)
      if (!ensureEgl() || !makePbufferCurrent()) return@post
      createWindowSurface()
      scheduleDisplayFrame()
    }
  }

  override fun clearOutputSurfaceInfo() {
    val previousSurface = outputSurface
    outputAvailable = false
    outputSurface = null
    glHandler.post {
      cancelDisplayFrame()
      clearFrameRateHint(previousSurface)
      makePbufferCurrent()
      destroyWindowSurface()
    }
  }

  override fun setChangeFrameRateStrategy(changeFrameRateStrategy: Int) {
    this.changeFrameRateStrategy = changeFrameRateStrategy
    glHandler.post { setOutputFrameRate() }
  }

  override fun onInputStreamChanged(
    inputType: Int,
    format: Format,
    startPositionUs: Long,
    firstFrameReleaseInstruction: Int,
    videoEffects: List<Effect>,
  ) {
    check(inputType == VideoSink.INPUT_TYPE_SURFACE) { "Media3FlowVideoSink requires Surface input" }
    check(videoEffects.isEmpty()) { "Media3FlowVideoSink does not accept stream video effects" }
    currentFormat = format
    streamStartPositionUs = startPositionUs
    allowFirstFrameBeforeStarted =
      firstFrameReleaseInstruction == VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY ||
        firstFrameReleaseInstruction == VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED
    flush(resetPosition = true)
    glHandler.post {
      if (format.width > 0 && format.height > 0 && makePbufferCurrent()) {
        configureInputSurface(format.width, format.height)
        ensureProcessingResources(format.width, format.height)
      }
      dispatchListener { it.onVideoSizeChanged(videoSize(format)) }
    }
  }

  override fun allowReleaseFirstFrameBeforeStarted() {
    allowFirstFrameBeforeStarted = true
  }

  override fun handleInputFrame(
    bufferPresentationTimeUs: Long,
    videoFrameHandler: VideoSink.VideoFrameHandler,
  ): Boolean {
    if (!initialized || disposed) return false
    synchronized(inputLock) {
      val buffered = pendingInput.size + storedFrameCount + if (inputInFlight) 1 else 0
      if (buffered >= MAX_QUEUED_FRAMES) return false
      val ptsUs = bufferPresentationTimeUs + inputTimestampAdjustmentUs
      pendingInput.addLast(PendingInput(ptsUs, streamGeneration, videoFrameHandler, System.nanoTime()))
      lastInputPtsUs = max(lastInputPtsUs, ptsUs)
    }
    return true
  }

  override fun handleInputBitmap(inputBitmap: Bitmap, bufferTimestampIterator: TimestampIterator): Boolean = false

  override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
    if (!initialized || disposed) return
    val speed = playbackSpeed
    val currentElapsedUs = SystemClock.elapsedRealtimeNanos() / 1_000L
    val elapsedSinceLoopUs =
      if (running && elapsedRealtimeUs != C.TIME_UNSET && currentElapsedUs >= elapsedRealtimeUs) {
        currentElapsedUs - elapsedRealtimeUs
      } else {
        0L
      }
    val currentPositionUs =
      positionUs + inputTimestampAdjustmentUs +
        (elapsedSinceLoopUs.toDouble() * speed.toDouble()).roundToLong()
    val clock = PlaybackClock(currentPositionUs, currentElapsedUs)
    playbackClock = clock
    releaseNextDecoderFrame(clock, speed)
    if (!running && allowFirstFrameBeforeStarted) {
      postOneShotRender()
    }
  }

  private fun postOneShotRender() {
    requestRenderAtDisplayFrame(null)
  }

  /** Latest-vsync-wins: a blocked swap coalesces requests instead of queuing stale catch-up renders. */
  private fun requestRenderAtDisplayFrame(frameTimeNanos: Long?) {
    renderFrameTimeNanos.set(frameTimeNanos ?: NO_FRAME_TIME_NANOS)
    renderRequestVersion.incrementAndGet()
    if (renderTaskPending.compareAndSet(false, true)) postRenderTask()
  }

  private fun postRenderTask() {
    val accepted = glHandler.post {
      val requestVersion = renderRequestVersion.get()
      val frameTimeNanos = renderFrameTimeNanos.get().takeUnless { it == NO_FRAME_TIME_NANOS }
      try {
        if (!disposed && initialized) renderAtDisplayFrame(frameTimeNanos)
      } catch (error: RuntimeException) {
        reportError(error)
      } finally {
        renderTaskPending.set(false)
        if (renderRequestVersion.get() != requestVersion &&
          renderTaskPending.compareAndSet(false, true)
        ) {
          postRenderTask()
        }
      }
    }
    if (!accepted) renderTaskPending.set(false)
  }

  /** Schedule vsync on the main looper so the GL thread can keep its next deadline visible while swap blocks. */
  private fun scheduleDisplayFrame() {
    if (!running || disposed || !outputAvailable || storedFrameCount <= 0 ||
      !frameCallbackScheduled.compareAndSet(false, true)
    ) return
    val registerCallback = Runnable {
      if (!running || disposed || !outputAvailable || storedFrameCount <= 0) {
        frameCallbackScheduled.set(false)
      } else {
        val currentChoreographer = choreographer ?: runCatching { Choreographer.getInstance() }
          .onFailure { Log.w(logTag, "Choreographer unavailable; using bounded timer pacing", it) }
          .getOrNull()
          ?.also { choreographer = it }
        if (currentChoreographer != null) {
          currentChoreographer.postFrameCallback(displayFrameCallback)
        } else {
          val delayMs = (1_000f / targetFps).roundToLong().coerceAtLeast(1L)
          if (!glHandler.postDelayed(fallbackFrameRunnable, delayMs)) frameCallbackScheduled.set(false)
        }
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      registerCallback.run()
    } else if (!mainHandler.post(registerCallback)) {
      frameCallbackScheduled.set(false)
    }
  }

  private fun cancelDisplayFrame() {
    if (frameCallbackScheduled.getAndSet(false)) {
      mainHandler.post { choreographer?.removeFrameCallback(displayFrameCallback) }
    }
    glHandler.removeCallbacks(fallbackFrameRunnable)
  }

  private fun renderAtDisplayFrame(frameTimeNanos: Long?) {
    if (!initialized || disposed || !outputAvailable) return
    if (!ensureEgl() || !makePbufferCurrent()) return
    pollGpuTimerQueries()
    pollCoverageBuffers()
    val speed = playbackSpeed
    val clock = playbackClock
    val nowElapsedUs = SystemClock.elapsedRealtimeNanos() / 1_000L
    val elapsedSinceClockUs =
      if (running && nowElapsedUs >= clock.elapsedRealtimeUs) nowElapsedUs - clock.elapsedRealtimeUs else 0L
    val positionUs = clock.positionUs + (elapsedSinceClockUs.toDouble() * speed.toDouble()).roundToLong()
    latestPositionUs = positionUs
    trimExpiredFrames(positionUs)
    renderAtPosition(positionUs, speed, frameTimeNanos)
    publishDiagnostics(lastState, lastBypassReason)
  }

  override fun join(renderNextFrameImmediately: Boolean) {
    if (renderNextFrameImmediately) {
      glHandler.post {
        lastOutputTick = -1L
        if (makePbufferCurrent()) {
          sourceFrames.lastOrNull { it.ptsUs <= latestPositionUs }
            ?.let { drawSourceFrame(it, System.nanoTime(), it.ptsUs) }
        }
      }
    }
  }

  override fun release() {
    if (disposed) return
    disposed = true
    running = false
    val previousSurface = outputSurface
    val discarded = ArrayList<PendingInput>()
    synchronized(inputLock) {
      while (pendingInput.isNotEmpty()) discarded += pendingInput.removeFirst()
    }
    discarded.forEach { runCatching { it.handler.skip() } }
    runCatching {
      runOnGlThreadSync {
        cancelDisplayFrame()
        clearFrameRateHint(previousSurface)
        makePbufferCurrent()
        destroyWindowSurface()
        inputSurface?.release()
        inputSurface = null
        inputSurfaceTexture?.release()
        inputSurfaceTexture = null
        destroyGlResources()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
          EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
          if (pbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
          if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
          EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        pbufferSurface = EGL14.EGL_NO_SURFACE
      }
    }
    glThread.quitSafely()
  }

  private fun releaseNextDecoderFrame(clock: PlaybackClock, speed: Float) {
    val nowNs = System.nanoTime()
    val pendingAndToken = synchronized(inputLock) {
      // Backpressure before Media3 releases into SurfaceTexture: a released frame cannot be
      // recovered if all owned GPU frame slots are still retaining earlier PTS values.
      if (disposed || pendingInput.isEmpty() ||
        !Media3FlowCadence.canReleaseNextDecoderFrame(
          storedFrameCount = storedFrameCount,
          maxStoredFrames = MAX_STORED_FRAMES,
          inputInFlight = inputInFlight,
        )
      ) return
      val pending = pendingInput.first()
      val releaseTimestampNs = Media3FlowCadence.inputReleaseTimeNs(
        framePtsUs = pending.ptsUs,
        clockPositionUs = clock.positionUs,
        nowNs = nowNs,
        speed = speed,
        maxLookAheadUs = MAX_INPUT_RELEASE_AHEAD_US,
      ) ?: return
      pendingInput.removeFirst()
      val token = FrameToken(pending.ptsUs, pending.generation, releaseTimestampNs.coerceAtLeast(nowNs))
      inputInFlight = true
      expectedTexturePts.addLast(token)
      pending to token
    }
    val pending = pendingAndToken.first
    val token = pendingAndToken.second
    recordCpuDuration(FlowCpuStage.INPUT_QUEUE_WAIT, (System.nanoTime() - pending.enqueuedAtNs).coerceAtLeast(0L))
    val handlerStartNs = System.nanoTime()
    try {
      // Handler.render is called on Media3's playback thread; GPU work stays on the dedicated GL thread.
      pending.handler.render(token.releaseTimestampNs)
    } catch (error: RuntimeException) {
      synchronized(inputLock) {
        if (expectedTexturePts.isNotEmpty()) {
          expectedTexturePts.removeLast()
          inputInFlight = false
        }
      }
      droppedFrames.incrementAndGet()
      dispatchListener { it.onFrameDropped() }
      reportError(error)
    } finally {
      recordCpuDuration(FlowCpuStage.FRAME_HANDLER_CALL, (System.nanoTime() - handlerStartNs).coerceAtLeast(0L))
    }
  }

  private fun configureInputSurface(width: Int, height: Int) {
    check(ensureEgl()) { "No GLES 3.1 context for Media3 Flow" }
    if (inputSurfaceTexture == null) {
      inputTextureId = createTexture(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        1,
        1,
        GLES20.GL_RGBA,
      )
      inputSurfaceTexture = SurfaceTexture(inputTextureId).apply {
        setOnFrameAvailableListener({ onInputFrameAvailable() }, glHandler)
      }
      inputSurface = Surface(inputSurfaceTexture)
    }
    inputSurfaceTexture?.setDefaultBufferSize(width, height)
  }

  private fun onInputFrameAvailable() {
    if (disposed) return
    traceGlForCurrentInput = isDebuggable && glProbeFramesRemaining > 0
    if (traceGlForCurrentInput) glProbeFramesRemaining--
    var tokenResolved = false
    try {
      check(makePbufferCurrent()) { "Unable to make the Media3 Flow input context current" }
      checkGlError("before SurfaceTexture.updateTexImage", debugProbeOnly = true, frameProbeOnly = true)
      val texture = checkNotNull(inputSurfaceTexture) { "Media3 Flow input SurfaceTexture is unavailable" }
      texture.updateTexImage()
      val surfaceTimestampNs = texture.timestamp
      checkGlError("SurfaceTexture.updateTexImage", debugProbeOnly = true, frameProbeOnly = true)
      val transform = FloatArray(16)
      texture.getTransformMatrix(transform)
      val token = synchronized(inputLock) {
        val value = if (expectedTexturePts.isEmpty()) null else expectedTexturePts.removeFirst()
        value
      }
      tokenResolved = true
      if (token == null || token.generation != streamGeneration) {
        droppedFrames.incrementAndGet()
        storedFrameCount = sourceFrames.size
        dispatchListener { it.onFrameAvailableForRendering() }
        return
      }
      lastSurfaceTimestampNs = surfaceTimestampNs
      lastFrameReleaseTimestampNs = token.releaseTimestampNs
      captureFrame(token.ptsUs, transform)
      storedFrameCount = sourceFrames.size
      dispatchListener { it.onFrameAvailableForRendering() }
      scheduleDisplayFrame()
    } catch (error: RuntimeException) {
      if (!tokenResolved) {
        synchronized(inputLock) {
          if (inputInFlight && expectedTexturePts.isNotEmpty()) {
            expectedTexturePts.removeFirst()
            inputInFlight = false
          }
        }
      }
      droppedFrames.incrementAndGet()
      dispatchListener { it.onFrameDropped() }
      Log.w(logTag, "Input frame capture failed; dropping this frame", error)
      reportError(error)
    } finally {
      synchronized(inputLock) {
        inputInFlight = false
      }
      traceGlForCurrentInput = false
    }
  }

  private fun captureFrame(ptsUs: Long, transform: FloatArray) {
    val previousPts = sourceFrames.lastOrNull()?.ptsUs
    if (previousPts != null && ptsUs <= previousPts) {
      skippedFrames++
      lastBypassReason = "non_monotonic_pts"
      return
    }
    evictOneFrameIfNeeded()
    val slot = framePool.firstOrNull { !it.inUse }
    if (slot == null) {
      droppedFrames.incrementAndGet()
      lastBypassReason = "bounded_queue_full"
      publishDiagnostics("source_drop", lastBypassReason)
      return
    }
    copyExternalTexture(slot.colorTexture, transform)
    if (flowAvailable) {
      try {
        dispatchLuma(slot)
      } catch (error: RuntimeException) {
        flowAvailable = false
        lastBypassReason = "luma_pass_error"
        lastState = "source_fallback"
        Log.w(logTag, "Luma preprocessing failed; keeping source-frame playback", error)
      }
    }
    slot.ptsUs = ptsUs
    slot.inUse = true
    sourceFrames.addLast(slot)
    if (originPtsUs == C.TIME_UNSET) {
      originPtsUs = streamStartPositionUs
        .takeIf { it != C.TIME_UNSET }
        ?.plus(inputTimestampAdjustmentUs)
        ?: ptsUs
      if (originPtsUs > ptsUs) originPtsUs = ptsUs
    }
    ptsHistory.addLast(ptsUs)
    while (ptsHistory.size > HISTORY_SIZE) ptsHistory.removeFirst()
    lastState = "gpu_ready"
    lastBypassReason = null
    if (sourceFrames.size >= 2) analyzeNewestPair()
  }

  private fun evictOneFrameIfNeeded() {
    if (sourceFrames.size < MAX_STORED_FRAMES) return
    val first = sourceFrames.firstOrNull() ?: return
    val second = sourceFrames.elementAtOrNull(1) ?: return
    if (retentionBoundaryUs(latestPositionUs) < second.ptsUs) return
    val expiredPts = first.ptsUs
    sourceFrames.removeFirst()
    first.inUse = false
    first.ptsUs = C.TIME_UNSET
    motionPairs.filter { it.frame0PtsUs == expiredPts || it.frame1PtsUs == expiredPts }
      .forEach { it.available = false; it.frame0PtsUs = C.TIME_UNSET; it.frame1PtsUs = C.TIME_UNSET }
  }

  private fun initializeMotionGpuTimerQueries() {
    if (gpuTimerQueryIds.isNotEmpty()) return
    val extensionCount = IntArray(1)
    GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, extensionCount, 0)
    val hasTimerExtension = (0 until extensionCount[0]).any { index ->
      GLES30.glGetStringi(GLES20.GL_EXTENSIONS, index) == "GL_EXT_disjoint_timer_query"
    }
    if (!hasTimerExtension) {
      Log.i(logTag, "GPU elapsed timer queries unavailable; reporting command-submit time only")
      return
    }
    val ids = IntArray(MAX_GPU_TIMER_QUERIES)
    GLES30.glGenQueries(ids.size, ids, 0)
    if (ids.any { it == 0 }) {
      GLES30.glDeleteQueries(ids.size, ids, 0)
      Log.w(logTag, "Could not allocate GPU timer queries")
      return
    }
    gpuTimerQueryIds = ids
    gpuTimerQueriesSupported = true
  }

  /** Advertises optional Qualcomm paths without changing the portable custom-shader renderer. */
  private fun logQualcommExtensionSupport() {
    val extensions = try {
      val extensionCount = IntArray(1)
      GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, extensionCount, 0)
      val discovered = mutableSetOf<String>()
      for (index in 0 until extensionCount[0].coerceAtLeast(0)) {
        GLES30.glGetStringi(GLES20.GL_EXTENSIONS, index)?.let(discovered::add)
      }
      discovered
    } catch (error: RuntimeException) {
      Log.w(logTag, "Could not query optional Qualcomm GLES extensions", error)
      return
    }
    Log.i(
      logTag,
      "QCOM GLES extension probe: motionEstimation=${"GL_QCOM_motion_estimation" in extensions} " +
        "frameExtrapolation=${"GL_QCOM_frame_extrapolation" in extensions}",
    )
  }

  private fun beginGpuTimerQuery(
    stage: FlowGpuStage,
    frame0PtsUs: Long = C.TIME_UNSET,
    frame1PtsUs: Long = C.TIME_UNSET,
    generation: Long = streamGeneration,
  ): PendingGpuTimerQuery? {
    if (!gpuTimerQueriesSupported) return null
    if (activeGpuTimerQuery != null) {
      skippedGpuTimerQueries++
      return null
    }
    pollGpuTimerQueries()
    val inUse = pendingGpuTimerQueries.mapTo(HashSet()) { it.id }
    val queryId = gpuTimerQueryIds.firstOrNull { it !in inUse }
    if (queryId == null) {
      skippedGpuTimerQueries++
      return null
    }
    val query = PendingGpuTimerQuery(queryId, stage, frame0PtsUs, frame1PtsUs, generation)
    GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queryId)
    activeGpuTimerQuery = query
    return query
  }

  private fun endGpuTimerQuery(query: PendingGpuTimerQuery?) {
    if (query == null || activeGpuTimerQuery?.id != query.id) return
    GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
    pendingGpuTimerQueries.addLast(query)
    activeGpuTimerQuery = null
  }

  /** Only available query results are read; a zero-timeout availability check never waits. */
  private fun pollGpuTimerQueries() {
    if (!gpuTimerQueriesSupported || pendingGpuTimerQueries.isEmpty()) return
    val pendingCount = pendingGpuTimerQueries.size
    repeat(pendingCount) {
      val pending = pendingGpuTimerQueries.removeFirst()
      val available = IntArray(1)
      GLES30.glGetQueryObjectuiv(pending.id, GLES30.GL_QUERY_RESULT_AVAILABLE, available, 0)
      if (available[0] == 0) {
        pendingGpuTimerQueries.addLast(pending)
        return@repeat
      }
      if (pending.generation != streamGeneration) return@repeat
      val disjoint = IntArray(1)
      GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, disjoint, 0)
      if (disjoint[0] != 0) {
        disjointGpuTimerResults++
        if (pending.stage == FlowGpuStage.MOTION_FORWARD || pending.stage == FlowGpuStage.MOTION_BACKWARD) {
          lastMotionGpuMs = null
        }
        return@repeat
      }
      val elapsed = IntArray(1)
      GLES30.glGetQueryObjectuiv(pending.id, GLES30.GL_QUERY_RESULT, elapsed, 0)
      val elapsedNs = elapsed[0].toLong() and 0xFFFF_FFFFL
      validGpuTimerResults++
      if (elapsedNs == 0L) {
        zeroGpuTimerResults++
        return@repeat
      }
      val stageSamples = gpuDurationSamplesNs[pending.stage.ordinal]
      stageSamples.addLast(elapsedNs)
      while (stageSamples.size > GPU_TIMING_HISTORY_SIZE) stageSamples.removeFirst()
      when (pending.stage) {
        FlowGpuStage.MOTION_FORWARD, FlowGpuStage.MOTION_BACKWARD -> updateMotionPairGpuTime(pending, elapsedNs)
        else -> Unit
      }
    }
  }

  private fun updateMotionPairGpuTime(query: PendingGpuTimerQuery, elapsedNs: Long) {
    val pair = motionPairs.firstOrNull {
      it.frame0PtsUs == query.frame0PtsUs && it.frame1PtsUs == query.frame1PtsUs
    } ?: return
    val elapsedMs = elapsedNs / 1_000_000f
    when (query.stage) {
      FlowGpuStage.MOTION_FORWARD -> pair.motionForwardGpuMs = elapsedMs
      FlowGpuStage.MOTION_BACKWARD -> pair.motionBackwardGpuMs = elapsedMs
      else -> return
    }
    val forwardMs = pair.motionForwardGpuMs
    val backwardMs = pair.motionBackwardGpuMs
    if (forwardMs != null && backwardMs != null) {
      pair.motionEstimateGpuMs = forwardMs + backwardMs
      lastMotionGpuMs = pair.motionEstimateGpuMs
    }
  }

  private fun recordCpuDuration(stage: FlowCpuStage, elapsedNs: Long) {
    synchronized(cpuTimingLock) {
      val samples = cpuDurationSamplesNs[stage.ordinal]
      samples.addLast(elapsedNs.coerceAtLeast(0L))
      while (samples.size > CPU_TIMING_HISTORY_SIZE) samples.removeFirst()
    }
  }

  private fun gpuTimingSnapshot(): FlowGpuTimingStats {
    val status = when {
      !gpuTimerQueriesSupported -> "unsupported"
      validGpuTimerResults > 0L && disjointGpuTimerResults > 0L -> "valid_with_disjoint_results"
      validGpuTimerResults > 0L -> "valid"
      disjointGpuTimerResults > 0L -> "disjoint_only"
      pendingGpuTimerQueries.isNotEmpty() -> "pending"
      skippedGpuTimerQueries > 0L -> "query_pool_limited"
      else -> "waiting_for_samples"
    }
    fun summary(stage: FlowGpuStage) =
      Media3FlowDiagnosticMath.summarizeNanoseconds(gpuDurationSamplesNs[stage.ordinal].toList())
    return FlowGpuTimingStats(
      supported = gpuTimerQueriesSupported,
      status = status,
      validResults = validGpuTimerResults,
      pendingResults = pendingGpuTimerQueries.size + if (activeGpuTimerQuery != null) 1 else 0,
      disjointResultsDiscarded = disjointGpuTimerResults,
      zeroDurationResults = zeroGpuTimerResults,
      skippedBecausePoolFull = skippedGpuTimerQueries,
      downsample = summary(FlowGpuStage.DOWNSAMPLE),
      motionForward = summary(FlowGpuStage.MOTION_FORWARD),
      motionBackward = summary(FlowGpuStage.MOTION_BACKWARD),
      consistencyAndWarp = summary(FlowGpuStage.CONSISTENCY_AND_WARP),
      presentationDraw = summary(FlowGpuStage.PRESENTATION_DRAW),
    )
  }

  private fun cpuTimingSnapshot(): FlowCpuTimingStats = synchronized(cpuTimingLock) {
    fun summary(stage: FlowCpuStage) =
      Media3FlowDiagnosticMath.summarizeNanoseconds(cpuDurationSamplesNs[stage.ordinal].toList())
    FlowCpuTimingStats(
      inputQueueWait = summary(FlowCpuStage.INPUT_QUEUE_WAIT),
      frameHandlerCall = summary(FlowCpuStage.FRAME_HANDLER_CALL),
      eglSwapWait = summary(FlowCpuStage.EGL_SWAP_WAIT),
    )
  }

  private fun pixelCoverageSnapshot(): FlowPixelCoverageStats =
    Media3FlowDiagnosticMath.summarizeCoverage(coverageSamples.toList()).copy(
      skippedSamples = skippedCoverageSamples,
      invalidSamples = invalidCoverageSamples,
    )

  private fun markCoverageFramePresented(framePtsUs: Long) {
    coverageBuffers.firstOrNull { it.fenceSync != 0L && it.framePtsUs == framePtsUs }?.presented = true
  }

  private fun initializeCoverageBuffers() {
    if (coverageBuffers.isNotEmpty()) return
    val ids = IntArray(COVERAGE_BUFFER_COUNT)
    GLES31.glGenBuffers(ids.size, ids, 0)
    val validIds = ids.filter { it != 0 }
    if (validIds.isEmpty()) {
      Log.w(logTag, "Could not allocate Flow coverage buffers; pixel coverage will be unavailable")
      return
    }
    validIds.forEach { id ->
      GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id)
      GLES31.glBufferData(
        GLES31.GL_SHADER_STORAGE_BUFFER,
        COVERAGE_BYTES_PER_GROUP,
        null,
        GLES30.GL_DYNAMIC_READ,
      )
      coverageBuffers += CoverageBufferSlot().apply {
        bufferId = id
        byteSize = COVERAGE_BYTES_PER_GROUP
      }
    }
    GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    coverageStatsAvailable = true
  }

  private fun acquireCoverageBuffer(framePtsUs: Long): CoverageBufferSlot? {
    coverageDispatchCount++
    if (coverageDispatchCount % COVERAGE_SAMPLE_INTERVAL != 0L) return null
    if (!coverageStatsAvailable) {
      skippedCoverageSamples++
      return null
    }
    val slot = coverageBuffers.firstOrNull { it.fenceSync == 0L }
    if (slot == null) {
      skippedCoverageSamples++
      return null
    }
    val groupsX = ceil(frameWidth / 8.0).toInt()
    val groupsY = ceil(frameHeight / 8.0).toInt()
    val groupsLong = groupsX.toLong() * groupsY.toLong()
    val byteSizeLong = groupsLong * COVERAGE_BYTES_PER_GROUP
    if (groupsLong <= 0L || byteSizeLong > Int.MAX_VALUE) {
      skippedCoverageSamples++
      return null
    }
    val byteSize = byteSizeLong.toInt()
    GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, slot.bufferId)
    if (slot.byteSize != byteSize) {
      GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, byteSize, null, GLES30.GL_DYNAMIC_READ)
      slot.byteSize = byteSize
    }
    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, FLOW_COVERAGE_BINDING, slot.bufferId)
    slot.groupCount = groupsLong.toInt()
    slot.expectedPixelCount = frameWidth.toLong() * frameHeight.toLong()
    slot.framePtsUs = framePtsUs
    slot.presented = false
    return slot
  }

  private fun pollCoverageBuffers() {
    if (!coverageStatsAvailable) return
    for (slot in coverageBuffers) {
      val fence = slot.fenceSync
      if (fence == 0L) continue
      val waitResult = GLES31.glClientWaitSync(fence, 0, 0L)
      if (waitResult == GL_TIMEOUT_EXPIRED) continue
      GLES31.glDeleteSync(fence)
      slot.fenceSync = 0L
      if (waitResult != GL_ALREADY_SIGNALED && waitResult != GL_CONDITION_SATISFIED) {
        invalidCoverageSamples++
        continue
      }
      if (!slot.presented) {
        skippedCoverageSamples++
        continue
      }
      GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, slot.bufferId)
      try {
        val mapped = runCatching {
          GLES31.glMapBufferRange(
            GLES31.GL_SHADER_STORAGE_BUFFER,
            0,
            slot.byteSize,
            GLES30.GL_MAP_READ_BIT,
          ) as? ByteBuffer
        }.getOrNull()
        if (mapped == null) {
          invalidCoverageSamples++
          continue
        }
        val data = mapped.order(ByteOrder.nativeOrder())
        var warped = 0L
        var sourceFallback = 0L
        var staticBlend = 0L
        var staticVectorLikelyMotion = 0L
        var staticVectorUncertainMotion = 0L
        var staticVectorNearZeroConfident = 0L
        var staticVectorNearZeroUncertain = 0L
        var interframeChangedWarp = 0L
        var interframeChangedSourceFallback = 0L
        var interframeChangedStaticBlend = 0L
        var interframeChangedPixels = 0L
        for (group in 0 until slot.groupCount) {
          val offset = group * COVERAGE_BYTES_PER_GROUP
          warped += data.getInt(offset).toLong() and 0xFFFF_FFFFL
          sourceFallback += data.getInt(offset + Int.SIZE_BYTES).toLong() and 0xFFFF_FFFFL
          staticBlend += data.getInt(offset + 2 * Int.SIZE_BYTES).toLong() and 0xFFFF_FFFFL
          interframeChangedWarp += data.getInt(offset + 4 * Int.SIZE_BYTES).toLong() and 0xFFFF_FFFFL
          interframeChangedSourceFallback += data.getInt(offset + 5 * Int.SIZE_BYTES).toLong() and 0xFFFF_FFFFL
          interframeChangedStaticBlend += data.getInt(offset + 6 * Int.SIZE_BYTES).toLong() and 0xFFFF_FFFFL
          interframeChangedPixels += data.getInt(offset + 7 * Int.SIZE_BYTES).toLong() and 0xFFFF_FFFFL
          when (data.getInt(offset + 3 * Int.SIZE_BYTES)) {
            1 -> staticVectorLikelyMotion++
            2 -> staticVectorUncertainMotion++
            3 -> staticVectorNearZeroConfident++
            4 -> staticVectorNearZeroUncertain++
          }
        }
        val staticVectorProbeSamples = staticVectorLikelyMotion + staticVectorUncertainMotion +
          staticVectorNearZeroConfident + staticVectorNearZeroUncertain
        val interframeChangedOutcomePixels = interframeChangedWarp + interframeChangedSourceFallback +
          interframeChangedStaticBlend
        val unmapped = runCatching { GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER) }.getOrDefault(false)
        if (!unmapped || warped + sourceFallback + staticBlend != slot.expectedPixelCount ||
          staticVectorProbeSamples > slot.groupCount.toLong() || staticVectorProbeSamples > staticBlend ||
          interframeChangedPixels != interframeChangedOutcomePixels ||
          interframeChangedPixels > slot.expectedPixelCount
        ) {
          invalidCoverageSamples++
          continue
        }
        coverageSamples.addLast(
          FlowCoverageSample(
            motionWarpPixels = warped,
            sourceFrameFallbackPixels = sourceFallback,
            staticBlendPixels = staticBlend,
            staticVectorLikelyMotionSamples = staticVectorLikelyMotion,
            staticVectorUncertainMotionSamples = staticVectorUncertainMotion,
            staticVectorNearZeroConfidentSamples = staticVectorNearZeroConfident,
            staticVectorNearZeroUncertainSamples = staticVectorNearZeroUncertain,
            interframeChangedPixels = interframeChangedPixels,
            interframeChangedWarpPixels = interframeChangedWarp,
            interframeChangedSourceFallbackPixels = interframeChangedSourceFallback,
            interframeChangedStaticBlendPixels = interframeChangedStaticBlend,
          ),
        )
        while (coverageSamples.size > COVERAGE_HISTORY_SIZE) coverageSamples.removeFirst()
      } finally {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
      }
    }
  }

  private fun destroyCoverageBuffers() {
    coverageBuffers.forEach { slot ->
      if (slot.fenceSync != 0L) GLES31.glDeleteSync(slot.fenceSync)
      if (slot.bufferId != 0) GLES31.glDeleteBuffers(1, intArrayOf(slot.bufferId), 0)
    }
    coverageBuffers.clear()
    coverageStatsAvailable = false
  }

  private fun analyzeNewestPair() {
    if (!flowAvailable || sourceFrames.size < 2) {
      lastBypassReason = "gpu_flow_unavailable"
      return
    }
    pollGpuTimerQueries()
    val frame0 = sourceFrames.elementAt(sourceFrames.size - 2)
    val frame1 = sourceFrames.last()
    val deltaUs = frame1.ptsUs - frame0.ptsUs
    if (deltaUs <= 0L || !Media3FlowCadence.needsInterpolation(deltaUs, targetFps, playbackSpeed)) {
      lastBypassReason = if (deltaUs <= 0L) "invalid_source_delta" else "source_meets_target"
      return
    }
    if (motionPairs.any { it.available && it.frame0PtsUs == frame0.ptsUs && it.frame1PtsUs == frame1.ptsUs }) return
    val reusable = motionPairs.firstOrNull {
      !it.available || retentionBoundaryUs(latestPositionUs) >= it.frame1PtsUs
    }
    if (reusable == null) {
      lastBypassReason = "motion_pair_queue_full"
      return
    }
    reusable.available = false
    reusable.frame0PtsUs = frame0.ptsUs
    reusable.frame1PtsUs = frame1.ptsUs
    reusable.motionEstimateGpuMs = null
    reusable.motionForwardGpuMs = null
    reusable.motionBackwardGpuMs = null
    val startNs = System.nanoTime()
    val queryGeneration = streamGeneration
    var stageQuery: PendingGpuTimerQuery? = null
    try {
      GLES31.glUseProgram(flowProgram)
      checkGlError("motion glUseProgram", frameProbeOnly = true)
      val sizeLocation = GLES31.glGetUniformLocation(flowProgram, "uSize")
      checkGlError("motion lookup uSize", frameProbeOnly = true)
      GLES31.glUniform2i(sizeLocation, processingWidth, processingHeight)
      checkGlError("motion uniform uSize", frameProbeOnly = true)
      val blockLocation = GLES31.glGetUniformLocation(flowProgram, "uBlock")
      checkGlError("motion lookup uBlock", frameProbeOnly = true)
      GLES31.glUniform1i(blockLocation, MEDIA3_FLOW_BLOCK_SIZE)
      checkGlError("motion uniform uBlock", frameProbeOnly = true)
      val stepLocation = GLES31.glGetUniformLocation(flowProgram, "uStep")
      checkGlError("motion lookup uStep", frameProbeOnly = true)
      GLES31.glUniform1i(stepLocation, MEDIA3_FLOW_GRID_STEP)
      checkGlError("motion uniform uStep", frameProbeOnly = true)
      val coarseStepLocation = GLES31.glGetUniformLocation(flowProgram, "uCoarseStep")
      checkGlError("motion lookup uCoarseStep", frameProbeOnly = true)
      GLES31.glUniform1i(coarseStepLocation, MEDIA3_FLOW_COARSE_SEARCH_STEP)
      checkGlError("motion uniform uCoarseStep", frameProbeOnly = true)
      GLES31.glBindImageTexture(0, frame0.lumaTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)
      checkGlError("forward motion bind input 0", frameProbeOnly = true)
      GLES31.glBindImageTexture(1, frame1.lumaTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)
      checkGlError("forward motion bind input 1", frameProbeOnly = true)
      GLES31.glBindImageTexture(2, reusable.forwardTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
      checkGlError("forward motion bind output", frameProbeOnly = true)
      stageQuery = beginGpuTimerQuery(FlowGpuStage.MOTION_FORWARD, frame0.ptsUs, frame1.ptsUs, queryGeneration)
      GLES31.glDispatchCompute(ceil(gridWidth / 8.0).toInt(), ceil(gridHeight / 8.0).toInt(), 1)
      checkGlError("forward motion dispatch", frameProbeOnly = true)
      GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
      checkGlError("forward motion barrier", frameProbeOnly = true)
      endGpuTimerQuery(stageQuery)
      stageQuery = null

      GLES31.glBindImageTexture(0, frame1.lumaTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)
      checkGlError("backward motion bind input 0", frameProbeOnly = true)
      GLES31.glBindImageTexture(1, frame0.lumaTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)
      checkGlError("backward motion bind input 1", frameProbeOnly = true)
      GLES31.glBindImageTexture(2, reusable.backwardTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
      checkGlError("backward motion bind output", frameProbeOnly = true)
      stageQuery = beginGpuTimerQuery(FlowGpuStage.MOTION_BACKWARD, frame0.ptsUs, frame1.ptsUs, queryGeneration)
      GLES31.glDispatchCompute(ceil(gridWidth / 8.0).toInt(), ceil(gridHeight / 8.0).toInt(), 1)
      checkGlError("backward motion dispatch", frameProbeOnly = true)
      GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
      checkGlError("backward motion barrier", frameProbeOnly = true)
      endGpuTimerQuery(stageQuery)
      stageQuery = null
      checkGlError("motion estimate")
      reusable.available = true
      lastMotionSubmitMs = (System.nanoTime() - startNs) / 1_000_000f
      reusable.motionEstimateSubmitMs = lastMotionSubmitMs
      reusable.motionEstimateGpuMs = null
      lastBypassReason = null
      lastState = "motion_estimated"
    } catch (error: RuntimeException) {
      reusable.available = false
      flowAvailable = false
      lastMotionSubmitMs = (System.nanoTime() - startNs) / 1_000_000f
      lastBypassReason = "motion_pass_error"
      lastState = "source_fallback"
      Log.w(logTag, "Motion pass failed; disabling GPU interpolation for this stream and retaining source frames", error)
    } finally {
      endGpuTimerQuery(stageQuery)
    }
  }

  private fun renderAtPosition(positionUs: Long, speed: Float, frameTimeNanos: Long?) {
    if ((!running && !allowFirstFrameBeforeStarted) || !outputAvailable || windowSurface == EGL14.EGL_NO_SURFACE) return
    if (sourceFrames.isEmpty()) return
    val sourceSnapshot = sourceFrames.toList()
    val origin = originPtsUs.takeIf { it != C.TIME_UNSET } ?: sourceSnapshot.first().ptsUs
    val tick = Media3FlowCadence.outputTick(positionUs, origin, targetFps, speed)
    val inputDone = synchronized(inputLock) { pendingInput.isEmpty() && !inputInFlight }
    val finalPtsUs = lastInputPtsUs
    val forceFinalFrame = (endOfInput || endOfCurrentInput) && inputDone &&
      finalPtsUs != C.TIME_UNSET && positionUs >= finalPtsUs && lastRenderedPtsUs < finalPtsUs
    val cadencePtsUs = tick?.let { Media3FlowCadence.outputTimestampUs(it, origin, targetFps, speed) }
      ?: sourceSnapshot.first().ptsUs
    val targetPtsUs = if (forceFinalFrame) finalPtsUs else cadencePtsUs.coerceAtMost(positionUs)
    if (tick != null && tick <= lastOutputTick && !forceFinalFrame) return
    val sourcePts = LongArray(sourceSnapshot.size) { sourceSnapshot[it].ptsUs }
    val sourceIndex = Media3FlowCadence.sourceFrameIndexAtOrBefore(sourcePts, targetPtsUs)
    val sourceFrame = sourceIndex?.let(sourceSnapshot::get)
    if (sourceFrame == null) {
      skippedFrames++
      lastBypassReason = "waiting_for_source_pts"
      logTimingTrace(positionUs, speed, targetPtsUs, null, sourceSnapshot.first().ptsUs, null, System.nanoTime())
      return
    }
    if (tick != null && lastOutputTick >= 0L) {
      val missed = Media3FlowCadence.skippedTicks(lastOutputTick, tick)
      if (missed > 0L) {
        droppedFrames.addAndGet(missed)
        missedOutputTicks.addAndGet(missed)
        lastBypassReason = "output_deadline_missed"
      }
    }
    val bracket = findSourcePair(sourceSnapshot, targetPtsUs)
    val pair = bracket?.let { (a, b) ->
      motionPairs.firstOrNull { it.available && it.frame0PtsUs == a.ptsUs && it.frame1PtsUs == b.ptsUs }
    }
    val deltaUs = bracket?.let { it.second.ptsUs - it.first.ptsUs } ?: 0L
    val estimatedMotionMs = pair?.let {
      Media3FlowCadence.motionEstimateMs(it.motionEstimateSubmitMs, it.motionEstimateGpuMs)
    } ?: Float.POSITIVE_INFINITY
    val synthesize = bracket != null && pair != null &&
      Media3FlowCadence.needsInterpolation(deltaUs, targetFps, speed) &&
      estimatedMotionMs < deltaUs / 1000f * ANALYSIS_DEADLINE_RESERVE
    val nowNs = System.nanoTime()
    val basePresentationNs = frameTimeNanos?.let {
      val refreshHz = displayHz.takeIf { rate -> rate.isFinite() && rate > 0f }?.toDouble() ?: targetFps.toDouble()
      it + (1_000_000_000.0 / refreshHz).roundToLong()
    } ?: nowNs
    val ptsOffsetNs = (((targetPtsUs - positionUs).toDouble() / speed.toDouble()) * 1000.0).toLong()
    val renderTimeNs = (basePresentationNs + ptsOffsetNs).coerceAtLeast(nowNs)
    logTimingTrace(positionUs, speed, targetPtsUs, sourceFrame.ptsUs, sourceSnapshot.first().ptsUs, renderTimeNs, nowNs)
    if (!makeWindowCurrent()) return
    var synthesized = false
    val submitted = if (synthesize && bracket != null && pair != null) {
      val synthesisResult = runCatching {
        dispatchSynthesis(bracket.first, bracket.second, pair, targetPtsUs)
        checkGlError("frame synthesis")
        drawTexture(outputTexture, renderTimeNs, targetPtsUs)
      }
      synthesized = synthesisResult.getOrDefault(false)
      if (synthesized) {
        true
      } else {
        synthesisResult.exceptionOrNull()?.let { error ->
          pair.available = false
          lastBypassReason = "synthesis_pass_error"
          Log.w(logTag, "Frame synthesis failed; drawing a source frame", error)
        }
        drawSourceFrame(sourceFrame, renderTimeNs, targetPtsUs)
      }
    } else {
      if (bracket != null && Media3FlowCadence.needsInterpolation(deltaUs, targetFps, speed)) {
        skippedFrames++
        if (lastBypassReason == null) lastBypassReason = "motion_not_ready_or_deadline"
      }
      drawSourceFrame(sourceFrame, renderTimeNs, targetPtsUs)
    }
    if (submitted) {
      if (synthesized) {
        markCoverageFramePresented(targetPtsUs)
        generatedFrames++
        generatedWallTimes.addLast(System.nanoTime())
        while (generatedWallTimes.size > OUTPUT_RATE_WINDOW) generatedWallTimes.removeFirst()
      }
      lastRenderedPtsUs = targetPtsUs
      outputWallTimes.addLast(frameTimeNanos ?: System.nanoTime())
      while (outputWallTimes.size > OUTPUT_RATE_WINDOW) outputWallTimes.removeFirst()
      if (!firstFrameReported) {
        firstFrameReported = true
        dispatchListener { it.onFirstFrameRendered() }
      }
      metadataListener?.onVideoFrameAboutToBeRendered(
        targetPtsUs,
        renderTimeNs,
        currentFormat ?: Format.Builder().build(),
        null,
      )
    } else {
      droppedFrames.incrementAndGet()
      dispatchListener { it.onFrameDropped() }
    }
    if (tick != null) lastOutputTick = tick
    allowFirstFrameBeforeStarted = false
    lastState = if (synthesized && submitted) "gpu_interpolated" else "source_frame_fallback"
    if (synthesized && submitted) lastBypassReason = null
  }

  private fun findSourcePair(frames: List<FrameSlot>, targetPtsUs: Long): Pair<FrameSlot, FrameSlot>? {
    for (index in 0 until frames.lastIndex) {
      val a = frames[index]
      val b = frames[index + 1]
      if (a.ptsUs < targetPtsUs && targetPtsUs < b.ptsUs) return a to b
    }
    return null
  }

  private fun dispatchSynthesis(a: FrameSlot, b: FrameSlot, pair: MotionPairSlot, targetPtsUs: Long) {
    val alpha = Media3FlowCadence.interpolationAlpha(a.ptsUs, b.ptsUs, targetPtsUs) ?: return
    val coverageSlot = acquireCoverageBuffer(targetPtsUs)
    GLES31.glUseProgram(synthProgram)
    bindTextureUnit(0, a.colorTexture)
    bindTextureUnit(1, b.colorTexture)
    GLES31.glUniform1i(GLES31.glGetUniformLocation(synthProgram, "uFrame0"), 0)
    GLES31.glUniform1i(GLES31.glGetUniformLocation(synthProgram, "uFrame1"), 1)
    GLES31.glUniform2i(GLES31.glGetUniformLocation(synthProgram, "uSize"), frameWidth, frameHeight)
    GLES31.glUniform2i(GLES31.glGetUniformLocation(synthProgram, "uMotionSize"), processingWidth, processingHeight)
    GLES31.glUniform2i(GLES31.glGetUniformLocation(synthProgram, "uGrid"), gridWidth, gridHeight)
    GLES31.glUniform1i(GLES31.glGetUniformLocation(synthProgram, "uStep"), MEDIA3_FLOW_GRID_STEP)
    GLES31.glUniform1f(
      GLES31.glGetUniformLocation(synthProgram, "uGridAnchorOffset"),
      Media3FlowGeometry.motionGridAnchorOffset(),
    )
    GLES31.glUniform1f(GLES31.glGetUniformLocation(synthProgram, "uAlpha"), alpha)
    GLES31.glUniform1i(
      GLES31.glGetUniformLocation(synthProgram, "uCoverageEnabled"),
      if (coverageSlot != null) 1 else 0,
    )
    (coverageSlot ?: coverageBuffers.firstOrNull())?.let {
      GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, FLOW_COVERAGE_BINDING, it.bufferId)
    }
    GLES31.glBindImageTexture(0, pair.forwardTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA16F)
    GLES31.glBindImageTexture(1, pair.backwardTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA16F)
    GLES31.glBindImageTexture(2, outputTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
    val synthesisQuery = beginGpuTimerQuery(
      FlowGpuStage.CONSISTENCY_AND_WARP,
      pair.frame0PtsUs,
      pair.frame1PtsUs,
    )
    var dispatchSubmitted = false
    try {
      GLES31.glDispatchCompute(ceil(frameWidth / 8.0).toInt(), ceil(frameHeight / 8.0).toInt(), 1)
      var barrierBits = GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT
      if (coverageSlot != null) {
        barrierBits = barrierBits or GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
      }
      GLES31.glMemoryBarrier(barrierBits)
      dispatchSubmitted = true
    } finally {
      endGpuTimerQuery(synthesisQuery)
    }
    if (coverageSlot != null) {
      if (dispatchSubmitted) {
        val fence = runCatching { GLES31.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0) }.getOrDefault(0L)
        if (fence != 0L) coverageSlot.fenceSync = fence else skippedCoverageSamples++
      } else {
        skippedCoverageSamples++
      }
    }
  }

  private fun drawSourceFrame(slot: FrameSlot, presentationTimeNs: Long, ptsUs: Long): Boolean =
    drawTexture(slot.colorTexture, presentationTimeNs, ptsUs)

  private fun drawTexture(textureId: Int, presentationTimeNs: Long, ptsUs: Long): Boolean {
    if (windowSurface == EGL14.EGL_NO_SURFACE || !makeWindowCurrent()) return false
    val width = outputResolution.width.takeIf { it > 0 } ?: frameWidth
    val height = outputResolution.height.takeIf { it > 0 } ?: frameHeight
    val geometry = Media3FlowGeometry.blitGeometry(frameWidth, frameHeight, width, height, videoAspect)
    val hasLetterbox = geometry.viewportWidth != width || geometry.viewportHeight != height
    if (hasLetterbox) {
      GLES20.glViewport(0, 0, width, height)
      GLES20.glClearColor(0f, 0f, 0f, 1f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }
    GLES20.glViewport(
      geometry.viewportX,
      geometry.viewportY,
      geometry.viewportWidth,
      geometry.viewportHeight,
    )
    GLES20.glUseProgram(blitProgram)
    bindTextureUnit(0, textureId)
    GLES20.glUniform1i(GLES20.glGetUniformLocation(blitProgram, "uImage"), 0)
    GLES20.glUniform2f(
      GLES20.glGetUniformLocation(blitProgram, "uTextureScale"),
      geometry.textureScaleX,
      geometry.textureScaleY,
    )
    GLES20.glUniform2f(
      GLES20.glGetUniformLocation(blitProgram, "uTextureOffset"),
      geometry.textureOffsetX,
      geometry.textureOffsetY,
    )
    val presentationQuery = beginGpuTimerQuery(FlowGpuStage.PRESENTATION_DRAW)
    try {
      drawFullscreenTriangle()
    } finally {
      endGpuTimerQuery(presentationQuery)
    }
    EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, presentationTimeNs.coerceAtLeast(0L))
    val swapStartNs = System.nanoTime()
    val swapped = EGL14.eglSwapBuffers(eglDisplay, windowSurface)
    recordCpuDuration(FlowCpuStage.EGL_SWAP_WAIT, (System.nanoTime() - swapStartNs).coerceAtLeast(0L))
    if (!swapped) Log.w(logTag, "eglSwapBuffers failed at ptsUs=$ptsUs error=0x${EGL14.eglGetError().toString(16)}")
    makePbufferCurrent()
    return swapped
  }

  private fun copyExternalTexture(destinationTexture: Int, transform: FloatArray) {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
    checkGlError("input copy bind framebuffer", frameProbeOnly = true)
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, destinationTexture, 0)
    checkGlError("input copy attach destination", frameProbeOnly = true)
    val framebufferStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
    checkGlError("input copy framebuffer status", frameProbeOnly = true)
    check(framebufferStatus == GLES20.GL_FRAMEBUFFER_COMPLETE) {
      "Media3 Flow input-copy framebuffer is incomplete"
    }
    GLES20.glViewport(0, 0, frameWidth, frameHeight)
    checkGlError("input copy viewport", frameProbeOnly = true)
    GLES20.glUseProgram(copyProgram)
    checkGlError("input copy glUseProgram", frameProbeOnly = true)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    checkGlError("input copy glActiveTexture", frameProbeOnly = true)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
    checkGlError("input copy bind external texture", frameProbeOnly = true)
    val externalLocation = GLES20.glGetUniformLocation(copyProgram, "uExternal")
    checkGlError("input copy lookup uExternal", frameProbeOnly = true)
    GLES20.glUniform1i(externalLocation, 0)
    checkGlError("input copy uniform uExternal", frameProbeOnly = true)
    val matrixLocation = GLES20.glGetUniformLocation(copyProgram, "uTextureMatrix")
    checkGlError("input copy lookup uTextureMatrix", frameProbeOnly = true)
    GLES20.glUniformMatrix4fv(matrixLocation, 1, false, transform, 0)
    checkGlError("input copy uniform uTextureMatrix", frameProbeOnly = true)
    drawFullscreenTriangle()
    checkGlError("input copy draw", frameProbeOnly = true)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    checkGlError("input copy unbind framebuffer", frameProbeOnly = true)
  }

  private fun dispatchLuma(slot: FrameSlot) {
    GLES31.glUseProgram(lumaProgram)
    checkGlError("luma glUseProgram", frameProbeOnly = true)
    bindTextureUnit(0, slot.colorTexture)
    checkGlError("luma bind color texture", frameProbeOnly = true)
    val colorLocation = GLES31.glGetUniformLocation(lumaProgram, "uColor")
    checkGlError("luma lookup uColor", frameProbeOnly = true)
    GLES31.glUniform1i(colorLocation, 0)
    checkGlError("luma uniform uColor", frameProbeOnly = true)
    val sizeLocation = GLES31.glGetUniformLocation(lumaProgram, "uSize")
    checkGlError("luma lookup uSize", frameProbeOnly = true)
    GLES31.glUniform2i(sizeLocation, processingWidth, processingHeight)
    checkGlError("luma uniform uSize", frameProbeOnly = true)
    GLES31.glBindImageTexture(0, slot.lumaTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
    checkGlError("luma bind output image", frameProbeOnly = true)
    val downsampleQuery = beginGpuTimerQuery(FlowGpuStage.DOWNSAMPLE)
    try {
      GLES31.glDispatchCompute(ceil(processingWidth / 8.0).toInt(), ceil(processingHeight / 8.0).toInt(), 1)
      checkGlError("luma dispatch", frameProbeOnly = true)
      GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
      checkGlError("luma barrier", frameProbeOnly = true)
    } finally {
      endGpuTimerQuery(downsampleQuery)
    }
  }

  private fun trimExpiredFrames(positionUs: Long) {
    val retentionBoundaryUs = retentionBoundaryUs(positionUs)
    while (sourceFrames.size >= 2 && sourceFrames.elementAt(1).ptsUs <= retentionBoundaryUs) {
      val first = sourceFrames.removeFirst()
      val expiredPts = first.ptsUs
      first.inUse = false
      first.ptsUs = C.TIME_UNSET
      motionPairs.filter { it.frame0PtsUs == expiredPts || it.frame1PtsUs == expiredPts }
        .forEach { it.available = false; it.frame0PtsUs = C.TIME_UNSET; it.frame1PtsUs = C.TIME_UNSET }
    }
    motionPairs.filter { it.available && retentionBoundaryUs >= it.frame1PtsUs }
      .forEach { it.available = false; it.frame0PtsUs = C.TIME_UNSET; it.frame1PtsUs = C.TIME_UNSET }
    storedFrameCount = sourceFrames.size
  }

  private fun retentionBoundaryUs(positionUs: Long): Long {
    val lookBehindUs = (1_000_000.0 * playbackSpeed.toDouble() / targetFps).roundToLong().coerceAtLeast(1L)
    return positionUs - lookBehindUs
  }

  private fun ensureProcessingResources(sourceWidth: Int, sourceHeight: Int) {
    val motionSize = Media3FlowGeometry.motionSize(sourceWidth, sourceHeight, maxDimension)
    val width = motionSize.width
    val height = motionSize.height
    if (sourceWidth == frameWidth && sourceHeight == frameHeight &&
      width == processingWidth && height == processingHeight && framePool.isNotEmpty()
    ) return
    clearSourceFrames()
    destroyFrameResources()
    frameWidth = sourceWidth
    frameHeight = sourceHeight
    processingWidth = width
    processingHeight = height
    val motionGridSize = Media3FlowGeometry.motionGridSize(width, height)
    gridWidth = motionGridSize.width
    gridHeight = motionGridSize.height
    outputTexture = createTexture(GLES20.GL_TEXTURE_2D, sourceWidth, sourceHeight, GLES30.GL_RGBA8)
    repeat(MAX_STORED_FRAMES) {
      framePool += FrameSlot().apply {
        colorTexture = createTexture(GLES20.GL_TEXTURE_2D, sourceWidth, sourceHeight, GLES30.GL_RGBA8)
        lumaTexture = createTexture(
          GLES20.GL_TEXTURE_2D,
          width,
          height,
          GLES30.GL_RGBA8,
        )
      }
    }
    repeat(MAX_MOTION_PAIRS) {
      motionPairs += MotionPairSlot().apply {
        forwardTexture = createTexture(GLES20.GL_TEXTURE_2D, gridWidth, gridHeight, GLES30.GL_RGBA16F)
        backwardTexture = createTexture(GLES20.GL_TEXTURE_2D, gridWidth, gridHeight, GLES30.GL_RGBA16F)
      }
    }
    initializeCoverageBuffers()
    flowAvailable = lumaProgram != 0 && flowProgram != 0 && synthProgram != 0
    if (!flowAvailable) lastBypassReason = "compute_shader_unavailable"
    setOutputFrameRate()
    publishDiagnostics(if (flowAvailable) "gpu_ready" else "source_fallback", lastBypassReason, force = true)
  }

  private fun clearSourceFrames() {
    sourceFrames.forEach { it.inUse = false; it.ptsUs = C.TIME_UNSET }
    sourceFrames.clear()
    motionPairs.forEach {
      it.available = false
      it.frame0PtsUs = C.TIME_UNSET
      it.frame1PtsUs = C.TIME_UNSET
      it.motionEstimateGpuMs = null
      it.motionForwardGpuMs = null
      it.motionBackwardGpuMs = null
    }
    lastMotionGpuMs = null
    lastMotionSubmitMs = 0f
    storedFrameCount = 0
  }

  private fun destroyFrameResources() {
    framePool.forEach { slot ->
      deleteTexture(slot.colorTexture)
      deleteTexture(slot.lumaTexture)
    }
    framePool.clear()
    motionPairs.forEach { slot ->
      deleteTexture(slot.forwardTexture)
      deleteTexture(slot.backwardTexture)
    }
    motionPairs.clear()
    deleteTexture(outputTexture)
    outputTexture = 0
  }

  private fun ensureEgl(preflightOnly: Boolean = false): Boolean {
    if (eglReady) {
      if (!preflightOnly && !computeProgramsCompiled) {
        if (!makePbufferCurrent()) return false
        compileComputePrograms()
        initializeMotionGpuTimerQueries()
        computeProgramsCompiled = true
      }
      return eglReady
    }
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
      val version = IntArray(2)
      if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false
    }
    if (eglConfig == null) {
      val configAttributes = intArrayOf(
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_NONE,
      )
      val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
      val configCount = IntArray(1)
      if (!EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0) || configCount[0] == 0) return false
      eglConfig = configs[0]
    }
    if (eglContext == EGL14.EGL_NO_CONTEXT) {
      val contextAttributes = if (
        EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS).orEmpty().contains("EGL_KHR_create_context")
      ) {
        intArrayOf(
          EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
          EGL_CONTEXT_MINOR_VERSION_KHR, 1,
          EGL14.EGL_NONE,
        )
      } else {
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
      }
      eglContext = EGL14.eglCreateContext(
        eglDisplay,
        checkNotNull(eglConfig),
        EGL14.EGL_NO_CONTEXT,
        contextAttributes,
        0,
      )
      if (eglContext == EGL14.EGL_NO_CONTEXT) return false
    }
    if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
      pbufferSurface = EGL14.eglCreatePbufferSurface(
        eglDisplay,
        checkNotNull(eglConfig),
        intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
        0,
      )
      if (pbufferSurface == EGL14.EGL_NO_SURFACE) return false
    }
    if (!makePbufferCurrent()) return false
    val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
    if (!isGles31(glVersion)) {
      lastBypassReason = "gles31_required"
      return false
    }
    if (!eglInfoLogged) {
      Log.i(
        logTag,
        "GLES context ready version=$glVersion vendor=${GLES20.glGetString(GLES20.GL_VENDOR)} renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}",
      )
      logQualcommExtensionSupport()
      eglInfoLogged = true
    }
    if (!graphicsProgramsCompiled) {
      compileGraphicsPrograms()
      graphicsProgramsCompiled = true
    }
    if (!preflightOnly && !computeProgramsCompiled) {
      compileComputePrograms()
      initializeMotionGpuTimerQueries()
      computeProgramsCompiled = true
    }
    if (framebuffer == 0) {
      val ids = IntArray(1)
      GLES20.glGenFramebuffers(1, ids, 0)
      framebuffer = ids[0]
    }
    eglReady = copyProgram != 0 && blitProgram != 0 && framebuffer != 0
    return eglReady
  }

  private fun compileGraphicsPrograms() {
    copyProgram = linkProgram(COPY_VERTEX_SHADER, COPY_EXTERNAL_FRAGMENT_SHADER)
    blitProgram = linkProgram(COPY_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
  }

  private fun compileComputePrograms() {
    lumaProgram = linkComputeProgram(LUMA_COMPUTE_SHADER)
    flowProgram = linkComputeProgram(FLOW_COMPUTE_SHADER)
    synthProgram = linkComputeProgram(SYNTH_COMPUTE_SHADER)
    flowAvailable = lumaProgram != 0 && flowProgram != 0 && synthProgram != 0
  }

  private fun makePbufferCurrent(): Boolean =
    eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT &&
      pbufferSurface != EGL14.EGL_NO_SURFACE &&
      EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)

  private fun makeWindowCurrent(): Boolean =
    windowSurface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)

  private fun destroyWindowSurface() {
    if (windowSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglDestroySurface(eglDisplay, windowSurface)
    }
    windowSurface = EGL14.EGL_NO_SURFACE
  }

  private fun createWindowSurface() {
    destroyWindowSurface()
    val surface = outputSurface ?: return
    if (!outputAvailable || !surface.isValid) return
    windowSurface = EGL14.eglCreateWindowSurface(
      eglDisplay,
      checkNotNull(eglConfig),
      surface,
      intArrayOf(EGL14.EGL_NONE),
      0,
    )
    if (windowSurface == EGL14.EGL_NO_SURFACE) {
      outputAvailable = false
      reportError(IllegalStateException("Unable to create Media3 Flow EGL window surface"))
    } else {
      setOutputFrameRate()
      currentFormat?.let { format -> dispatchListener { it.onVideoSizeChanged(videoSize(format)) } }
    }
  }

  private fun createTexture(target: Int, width: Int, height: Int, internalFormat: Int): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    checkGlError("texture glGenTextures", debugProbeOnly = true)
    val id = ids[0]
    GLES20.glBindTexture(target, id)
    checkGlError("texture glBindTexture", debugProbeOnly = true)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    checkGlError("texture min filter", debugProbeOnly = true)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    checkGlError("texture mag filter", debugProbeOnly = true)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    checkGlError("texture wrap S", debugProbeOnly = true)
    GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    checkGlError("texture wrap T", debugProbeOnly = true)
    if (target == GLES20.GL_TEXTURE_2D) {
      // GLES 3.1 only permits immutable texture objects in glBindImageTexture.
      // All compute input/output and sampled 2D images use one immutable mip level.
      GLES30.glTexStorage2D(target, 1, internalFormat, width, height)
      checkGlError("immutable texture allocation format=0x${internalFormat.toString(16)} size=${width}x$height", debugProbeOnly = true)
    }
    return id
  }

  private fun deleteTexture(id: Int) {
    if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
  }

  private fun bindTextureUnit(unit: Int, textureId: Int) {
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
  }

  private fun drawFullscreenTriangle() {
    vertexBuffer.position(0)
    GLES20.glEnableVertexAttribArray(0)
    checkGlError("fullscreen enable vertex attribute", frameProbeOnly = true)
    GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
    checkGlError("fullscreen vertex attribute pointer", frameProbeOnly = true)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    checkGlError("fullscreen draw", frameProbeOnly = true)
    GLES20.glDisableVertexAttribArray(0)
    checkGlError("fullscreen disable vertex attribute", frameProbeOnly = true)
  }

  private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (vertex == 0 || fragment == 0) return 0
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertex)
    GLES20.glAttachShader(program, fragment)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    GLES20.glDeleteShader(vertex)
    GLES20.glDeleteShader(fragment)
    if (status[0] == 0) {
      Log.w(logTag, "Graphics shader link failed: ${GLES20.glGetProgramInfoLog(program)}")
      GLES20.glDeleteProgram(program)
      return 0
    }
    return program
  }

  private fun linkComputeProgram(source: String): Int {
    val shader = compileShader(GLES31.GL_COMPUTE_SHADER, source)
    if (shader == 0) return 0
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, shader)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    GLES20.glDeleteShader(shader)
    if (status[0] == 0) {
      Log.w(logTag, "Compute shader link failed: ${GLES20.glGetProgramInfoLog(program)}")
      GLES20.glDeleteProgram(program)
      return 0
    }
    return program
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
      Log.w(logTag, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
      GLES20.glDeleteShader(shader)
      return 0
    }
    return shader
  }

  private fun destroyGlResources() {
    destroyFrameResources()
    destroyCoverageBuffers()
    intArrayOf(copyProgram, blitProgram, lumaProgram, flowProgram, synthProgram)
      .filter { it != 0 }
      .forEach { GLES20.glDeleteProgram(it) }
    copyProgram = 0
    blitProgram = 0
    lumaProgram = 0
    flowProgram = 0
    synthProgram = 0
    if (gpuTimerQueryIds.isNotEmpty()) {
      GLES30.glDeleteQueries(gpuTimerQueryIds.size, gpuTimerQueryIds, 0)
    }
    gpuTimerQueryIds = IntArray(0)
    gpuTimerQueriesSupported = false
    pendingGpuTimerQueries.clear()
    activeGpuTimerQuery = null
    lastMotionGpuMs = null
    if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
    framebuffer = 0
    deleteTexture(inputTextureId)
    inputTextureId = 0
  }

  private fun setOutputFrameRate() {
    val surface = outputSurface ?: return
    if (android.os.Build.VERSION.SDK_INT >= 30 && surface.isValid) {
      val frameRate = if (running && changeFrameRateStrategy != C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF) {
        targetFps.toFloat()
      } else {
        0f
      }
      val policy = if (android.os.Build.VERSION.SDK_INT >= 31) {
        Api31.frameRateSurfacePolicy(appContext)
      } else {
        null
      }
      outputFrameRateHintFps = frameRate
      matchContentFrameRatePreferenceLabel = policy?.userPreferenceLabel ?: "unavailable_api30"
      surfaceFrameRateChangeStrategyLabel = when {
        frameRate == 0f -> "off"
        policy != null -> policy.strategyLabel
        else -> "only_if_seamless_default"
      }
      logFrameRateRequest()
      runCatching {
        if (policy != null) {
          Api31.setFrameRate(surface, frameRate, policy.changeStrategy)
        } else {
          Api30.setFrameRate(surface, frameRate)
        }
      }
        .onFailure { Log.d(logTag, "Unable to set display frame-rate hint", it) }
    }
  }

  private fun clearFrameRateHint(surface: Surface?) {
    if (android.os.Build.VERSION.SDK_INT >= 30 && surface?.isValid == true) {
      runCatching { Api30.setFrameRate(surface, 0f) }
        .onFailure { Log.d(logTag, "Unable to clear display frame-rate hint", it) }
    }
  }

  private fun publishDiagnostics(state: String, reason: String? = lastBypassReason, force: Boolean = false) {
    val nowNs = System.nanoTime()
    if (!force && nowNs - lastMetricsAtNs < METRICS_INTERVAL_NS) return
    lastMetricsAtNs = nowNs
    val outputFps = if (outputWallTimes.size >= 2) {
      val elapsedNs = outputWallTimes.last() - outputWallTimes.first()
      if (elapsedNs > 0L) (outputWallTimes.size - 1) * 1_000_000_000f / elapsedNs else 0f
    } else 0f
    val generatedFps = if (generatedWallTimes.size >= 2) {
      val elapsedNs = generatedWallTimes.last() - generatedWallTimes.first()
      if (elapsedNs > 0L) (generatedWallTimes.size - 1) * 1_000_000_000f / elapsedNs else 0f
    } else 0f
    val diagnostics = Media3FlowDiagnostics(
      enabled = true,
      sourceFps = Media3FlowCadence.estimateSourceFps(ptsHistory.toList()),
      outputFps = outputFps,
      generatedFps = generatedFps,
      targetFps = targetFps,
      generatedFrames = generatedFrames,
      droppedFrames = droppedFrames.get(),
      missedOutputTicks = missedOutputTicks.get(),
      skippedFrames = skippedFrames,
      motionEstimateSubmitMs = lastMotionSubmitMs,
      motionEstimateGpuMs = lastMotionGpuMs,
      confidence = null,
      motionGridWidth = gridWidth,
      motionGridHeight = gridHeight,
      processingWidth = processingWidth,
      processingHeight = processingHeight,
      state = state,
      bypassReason = reason,
      gpuTimings = gpuTimingSnapshot(),
      cpuTimings = cpuTimingSnapshot(),
      pixelCoverage = pixelCoverageSnapshot(),
    )
    runCatching { onDiagnostics(diagnostics) }
    logFlowSummary(diagnostics, nowNs)
  }

  private fun logFlowSummary(diagnostics: Media3FlowDiagnostics, nowNs: Long) {
    if (!isDebuggable) return
    val signature = "${diagnostics.state}|${diagnostics.bypassReason ?: "none"}"
    val stateChanged = signature != lastFlowSummarySignature
    val minimumIntervalNs = if (stateChanged) FLOW_STATE_LOG_MIN_INTERVAL_NS else FLOW_SUMMARY_LOG_INTERVAL_NS
    if (lastFlowSummaryLogAtNs != 0L && nowNs - lastFlowSummaryLogAtNs < minimumIntervalNs) return
    lastFlowSummaryLogAtNs = nowNs
    lastFlowSummarySignature = signature

    val inputSize = currentFormat?.let { "${it.width}x${it.height}" } ?: "unknown"
    val gpuTiming = diagnostics.gpuTimings
    val cpuTiming = diagnostics.cpuTimings
    val coverage = diagnostics.pixelCoverage
    val gpuElapsed = when {
      !gpuTimerQueriesSupported -> "unsupported"
      diagnostics.motionEstimateGpuMs == null -> "pending"
      else -> "${formatLogFloat(diagnostics.motionEstimateGpuMs * 1_000f)}us"
    }
    val confidence = diagnostics.confidence?.let { formatLogFloat(it) } ?: "n/a"
    val gpuStages = "downsample=${formatFlowDuration(gpuTiming.downsample)} " +
      "motionF=${formatFlowDuration(gpuTiming.motionForward)} motionB=${formatFlowDuration(gpuTiming.motionBackward)} " +
      "consistencyWarp=${formatFlowDuration(gpuTiming.consistencyAndWarp)} " +
      "presentation=${formatFlowDuration(gpuTiming.presentationDraw)}"
    val cpuStages = "queueWait=${formatFlowDuration(cpuTiming.inputQueueWait)} " +
      "frameHandler=${formatFlowDuration(cpuTiming.frameHandlerCall)} " +
      "eglSwapWait=${formatFlowDuration(cpuTiming.eglSwapWait)}"
    val coverageSummary = "samples=${coverage.samples} warp=${coverage.motionWarpPixels}(${formatFlowPercent(coverage.motionWarpPercent)}%) " +
      "sourceFallback=${coverage.sourceFrameFallbackPixels}(${formatFlowPercent(coverage.sourceFrameFallbackPercent)}%) " +
      "staticBlend=${coverage.staticBlendPixels}(${formatFlowPercent(coverage.staticBlendPercent)}%) " +
      "interframeChangedPixels=${coverage.interframeChangedPixels}/${coverage.sampledPixels}" +
      "(${formatFlowPercent(coverage.interframeChangedFramePercent)}%ofFrame,weightedAbsRgbDeltaMin=$INTERFRAME_CHANGE_DIAGNOSTIC_LUMA_THRESHOLD," +
      "staticBlendCut=$STATIC_BLEND_LUMA_DELTA_THRESHOLD) " +
      "changedOutcomesDenom=${coverage.interframeChangedPixels} " +
      "changedWarp=${coverage.interframeChangedWarpPixels}(${formatFlowPercent(coverage.interframeChangedWarpPercent)}%) " +
      "changedSourceFallback=${coverage.interframeChangedSourceFallbackPixels}" +
      "(${formatFlowPercent(coverage.interframeChangedSourceFallbackPercent)}%) " +
      "changedStaticBlend=${coverage.interframeChangedStaticBlendPixels}" +
      "(${formatFlowPercent(coverage.interframeChangedStaticBlendPercent)}%) " +
      "pixels=${coverage.sampledPixels} skipped=${coverage.skippedSamples} invalid=${coverage.invalidSamples} " +
      "sampleEveryDispatch=$COVERAGE_SAMPLE_INTERVAL " +
      "staticVectorProbe=${coverage.staticVectorProbeSamples}(magnitudeCutoffProcessingPx=0.5,reliabilityCutoff=0.15) " +
      "likely=${coverage.staticVectorLikelyMotionSamples}(${formatFlowPercent(coverage.staticVectorLikelyMotionPercent)}%) " +
      "uncertain=${coverage.staticVectorUncertainMotionSamples}(${formatFlowPercent(coverage.staticVectorUncertainMotionPercent)}%) " +
      "nearZeroConfident=${coverage.staticVectorNearZeroConfidentSamples} " +
      "(${formatFlowPercent(coverage.staticVectorNearZeroConfidentPercent)}%) " +
      "nearZeroUncertain=${coverage.staticVectorNearZeroUncertainSamples} " +
      "(${formatFlowPercent(coverage.staticVectorNearZeroUncertainPercent)}%)"
    Log.i(
      logTag,
      "flow_summary state=${diagnostics.state} bypass=${diagnostics.bypassReason ?: "none"} " +
        "positionUs=$latestPositionUs speed=${formatLogFloat(playbackSpeed)} " +
        "sourceFps=${formatLogFloat(diagnostics.sourceFps)} targetFps=${diagnostics.targetFps} " +
        "eglSwapFps=${formatLogFloat(diagnostics.outputFps)} generatedFps=${formatLogFloat(diagnostics.generatedFps)} " +
        "generatedTotal=${diagnostics.generatedFrames} dropsTotal=${diagnostics.droppedFrames} " +
        "missedOutputTicksTotal=${diagnostics.missedOutputTicks} skippedTotal=${diagnostics.skippedFrames} " +
        "input=$inputSize processing=${diagnostics.processingWidth}x${diagnostics.processingHeight} " +
        "motionGrid=${diagnostics.motionGridWidth}x${diagnostics.motionGridHeight} " +
        "motionSubmitCpuMs=${formatLogFloat(diagnostics.motionEstimateSubmitMs)} " +
        "motionGpuElapsed=$gpuElapsed gpuTimerStatus=${gpuTiming.status} " +
        "gpuQueryCounts=valid:${gpuTiming.validResults},pending:${gpuTiming.pendingResults}," +
        "disjoint:${gpuTiming.disjointResultsDiscarded},zero:${gpuTiming.zeroDurationResults}," +
        "skipped:${gpuTiming.skippedBecausePoolFull} gpuStagesUs={$gpuStages} " +
        "cpuStagesUs={$cpuStages} coverage={$coverageSummary} " +
        "confidence=$confidence displayReportedHz=${formatLogFloat(displayRefreshRate(appContext))} " +
        "displayModeInitialHz=${formatLogFloat(displayHz)} surfaceHintFps=${formatLogFloat(outputFrameRateHintFps)} " +
        "matchContentPreference=$matchContentFrameRatePreferenceLabel " +
        "surfaceSwitchStrategy=$surfaceFrameRateChangeStrategyLabel " +
        "media3Strategy=${if (changeFrameRateStrategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF) "off" else "only_if_seamless"}",
    )
  }

  private fun logFrameRateRequest() {
    if (!isDebuggable) return
    val media3Policy = if (changeFrameRateStrategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF) {
      "off"
    } else {
      "only_if_seamless"
    }
    val signature = "$outputFrameRateHintFps|$matchContentFrameRatePreferenceLabel|" +
      "$surfaceFrameRateChangeStrategyLabel|$running|$media3Policy"
    if (signature == lastFrameRateRequestLogSignature) return
    lastFrameRateRequestLogSignature = signature
    Log.i(
      logTag,
      "frame_rate_request hintFps=${formatLogFloat(outputFrameRateHintFps)} targetFps=$targetFps " +
        "running=$running media3Strategy=$media3Policy " +
        "matchContentPreference=$matchContentFrameRatePreferenceLabel " +
        "surfaceSwitchStrategy=$surfaceFrameRateChangeStrategyLabel " +
        "initialDisplayHz=${formatLogFloat(displayHz)}",
    )
  }

  private fun formatLogFloat(value: Float): String =
    if (value.isFinite()) {
      String.format(java.util.Locale.US, "%.2f", value)
    } else {
      "n/a"
    }

  private fun formatFlowDuration(summary: FlowDurationSummary): String {
    if (summary.sampleCount == 0) return "n/a"
    fun value(durationUs: Double?): String = durationUs?.let {
      String.format(java.util.Locale.US, "%.1f", it)
    } ?: "n/a"
    return "${value(summary.averageUs)}/${value(summary.p95Us)}/${value(summary.maximumUs)}us(n=${summary.sampleCount})"
  }

  private fun formatFlowPercent(value: Float?): String = value?.let {
    String.format(java.util.Locale.US, "%.1f", it)
  } ?: "n/a"

  private fun logTimingTrace(
    positionUs: Long,
    speed: Float,
    targetPtsUs: Long,
    sourcePtsUs: Long?,
    nextSourcePtsUs: Long?,
    presentationTimeNs: Long?,
    nowNs: Long,
  ) {
    if (!isDebuggable || nowNs - lastTimingLogAtNs < METRICS_INTERVAL_NS) return
    lastTimingLogAtNs = nowNs
    val sourceLeadUs = sourcePtsUs?.let { it - targetPtsUs }?.toString() ?: "none"
    val surfaceTimestampDeltaNs = if (
      lastSurfaceTimestampNs != C.TIME_UNSET && lastFrameReleaseTimestampNs != C.TIME_UNSET
    ) lastSurfaceTimestampNs - lastFrameReleaseTimestampNs else C.TIME_UNSET
    Log.i(
      logTag,
      "clock playerPositionUs=$positionUs speed=$speed targetPtsUs=$targetPtsUs " +
        "targetOffsetUs=${targetPtsUs - positionUs} sourcePtsUs=${sourcePtsUs ?: "none"} " +
        "sourceLeadUs=$sourceLeadUs nextSourcePtsUs=${nextSourcePtsUs ?: "none"} " +
        "inputReleaseTimestampNs=$lastFrameReleaseTimestampNs inputSurfaceTimestampNs=$lastSurfaceTimestampNs " +
        "inputTimestampDeltaNs=$surfaceTimestampDeltaNs presentationTimeNs=${presentationTimeNs ?: "none"} nowNs=$nowNs",
    )
  }

  private fun dispatchListener(action: (VideoSink.Listener) -> Unit) {
    val current = listener
    val executor = listenerExecutor
    runCatching { executor.execute { action(current) } }
  }

  private fun reportError(error: Throwable) {
    val format = currentFormat ?: Format.Builder().build()
    dispatchListener { it.onError(VideoSink.VideoSinkException(error, format)) }
  }

  private fun videoSize(format: Format): VideoSize =
    VideoSize(format.width, format.height, format.rotationDegrees, format.pixelWidthHeightRatio)

  private fun checkGlError(stage: String, debugProbeOnly: Boolean = false, frameProbeOnly: Boolean = false) {
    if (debugProbeOnly && !isDebuggable) return
    if (frameProbeOnly && (!isDebuggable || !traceGlForCurrentInput)) return
    val errors = mutableListOf<Int>()
    var error = GLES20.glGetError()
    while (error != GLES20.GL_NO_ERROR && errors.size < 16) {
      errors += error
      error = GLES20.glGetError()
    }
    check(errors.isEmpty()) {
      "$stage GLES error(s) ${errors.joinToString { "0x${it.toString(16)}" }}"
    }
  }

  private fun isGles31(version: String): Boolean {
    val match = GLES_VERSION_REGEX.find(version) ?: return false
    val major = match.groupValues[1].toIntOrNull() ?: return false
    val minor = match.groupValues[2].toIntOrNull() ?: return false
    return major > 3 || (major == 3 && minor >= 1)
  }

  private fun <T> runOnGlThreadSync(timeoutMs: Long = GL_INIT_TIMEOUT_MS, block: () -> T): T {
    if (Looper.myLooper() == glThread.looper) return block()
    val latch = CountDownLatch(1)
    var result: Any? = null
    var failure: Throwable? = null
    check(glHandler.post {
      try {
        result = block()
      } catch (error: Throwable) {
        failure = error
      } finally {
        latch.countDown()
      }
    }) { "Media3 Flow GL thread is shutting down" }
    check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "Timed out waiting for Media3 Flow GL initialization" }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  companion object {
    private const val MAX_STORED_FRAMES = 3
    private const val MAX_MOTION_PAIRS = 2
    private const val MAX_QUEUED_FRAMES = 5
    private const val MAX_INPUT_RELEASE_AHEAD_US = 100_000L
    private const val HISTORY_SIZE = 24
    private const val OUTPUT_RATE_WINDOW = 31
    private const val ANALYSIS_DEADLINE_RESERVE = 0.85f
    private const val METRICS_INTERVAL_NS = 500_000_000L
    private const val FLOW_SUMMARY_LOG_INTERVAL_NS = 5_000_000_000L
    private const val FLOW_STATE_LOG_MIN_INTERVAL_NS = 1_000_000_000L
    private const val GL_INIT_TIMEOUT_MS = 5_000L
    private const val MAX_GPU_TIMER_QUERIES = 64
    private const val GPU_TIMING_HISTORY_SIZE = 90
    private const val CPU_TIMING_HISTORY_SIZE = 90
    private const val COVERAGE_BUFFER_COUNT = 3
    private const val COVERAGE_HISTORY_SIZE = 12
    private const val COVERAGE_SAMPLE_INTERVAL = 8L
    private const val COVERAGE_BYTES_PER_GROUP = 8 * Int.SIZE_BYTES
    private const val FLOW_COVERAGE_BINDING = 3
    private const val INTERFRAME_CHANGE_DIAGNOSTIC_LUMA_THRESHOLD = 0.01f
    private const val STATIC_BLEND_LUMA_DELTA_THRESHOLD = 0.018f
    private const val NO_FRAME_TIME_NANOS = Long.MIN_VALUE
    private const val GL_TIME_ELAPSED_EXT = 0x88BF
    private const val GL_GPU_DISJOINT_EXT = 0x8FBB
    private const val GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117
    private const val GL_ALREADY_SIGNALED = 0x911A
    private const val GL_TIMEOUT_EXPIRED = 0x911B
    private const val GL_CONDITION_SATISFIED = 0x911C
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040
    private const val EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB
    private val GLES_VERSION_REGEX = Regex("OpenGL ES (\\d+)\\.(\\d+)")

    @JvmStatic
    fun supportsFormat(format: Format): Boolean {
      if (format.width < 32 || format.height < 32 || format.rotationDegrees != 0 ||
        abs(format.pixelWidthHeightRatio - 1f) > 0.001f || format.drmInitData != null
      ) return false
      val mime = format.sampleMimeType.orEmpty().lowercase()
      val codecs = format.codecs.orEmpty().lowercase()
      if (mime.contains("dolby-vision") || codecs.startsWith("dvhe") || codecs.startsWith("dvh1")) return false
      val transfer = format.colorInfo?.colorTransfer ?: return false
      return transfer == C.COLOR_TRANSFER_SDR
    }

    @JvmStatic
    fun deviceSupportsGles31(context: Context): Boolean = runCatching {
      val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      manager.deviceConfigurationInfo.reqGlEsVersion >= 0x0003_0001
    }.getOrDefault(false)

    private const val COPY_VERTEX_SHADER = """
      #version 300 es
      layout(location = 0) in vec2 aPosition;
      out vec2 vUv;
      void main() {
        vUv = aPosition * 0.5 + 0.5;
        gl_Position = vec4(aPosition, 0.0, 1.0);
      }
    """

    private const val COPY_EXTERNAL_FRAGMENT_SHADER = """
      #version 300 es
      #extension GL_OES_EGL_image_external_essl3 : require
      precision highp float;
      in vec2 vUv;
      out vec4 outColor;
      uniform samplerExternalOES uExternal;
      uniform mat4 uTextureMatrix;
      void main() {
        vec2 uv = (uTextureMatrix * vec4(vUv, 0.0, 1.0)).xy;
        outColor = texture(uExternal, uv);
      }
    """

    private const val BLIT_FRAGMENT_SHADER = """
      #version 300 es
      precision highp float;
      in vec2 vUv;
      out vec4 outColor;
      uniform sampler2D uImage;
      uniform vec2 uTextureScale;
      uniform vec2 uTextureOffset;
      void main() { outColor = texture(uImage, uTextureOffset + vUv * uTextureScale); }
    """

    private const val LUMA_COMPUTE_SHADER = """
      #version 310 es
      layout(local_size_x = 8, local_size_y = 8) in;
      precision highp float;
      layout(binding = 0) uniform sampler2D uColor;
      layout(rgba8, binding = 0) writeonly uniform highp image2D uLuma;
      uniform ivec2 uSize;
      void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (any(greaterThanEqual(p, uSize))) return;
        vec2 uv = (vec2(p) + vec2(0.5)) / vec2(uSize);
        vec3 c = texture(uColor, uv).rgb;
        float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
        imageStore(uLuma, p, vec4(y, 0.0, 0.0, 1.0));
      }
    """

    internal const val FLOW_COMPUTE_SHADER = """
      #version 310 es
      layout(local_size_x = 8, local_size_y = 8) in;
      precision highp float;
      layout(rgba8, binding = 0) readonly uniform highp image2D uSource;
      layout(rgba8, binding = 1) readonly uniform highp image2D uTarget;
      layout(rgba16f, binding = 2) writeonly uniform highp image2D uFlow;
      uniform ivec2 uSize;
      uniform int uBlock;
      uniform int uStep;
      uniform int uCoarseStep;
      float blockSad(ivec2 origin, ivec2 offset, int stride) {
        float sad = 0.0;
        float samples = 0.0;
        for (int y = 0; y < uBlock; y += stride) {
          for (int x = 0; x < uBlock; x += stride) {
            ivec2 p = origin + ivec2(x, y);
            sad += abs(imageLoad(uSource, p).r - imageLoad(uTarget, p + offset).r);
            samples += 1.0;
          }
        }
        return sad / max(samples, 1.0);
      }
      bool offsetIsValid(ivec2 origin, ivec2 offset, ivec2 limit) {
        ivec2 targetOrigin = origin + offset;
        return all(greaterThanEqual(targetOrigin, ivec2(0))) &&
          all(lessThanEqual(targetOrigin, limit));
      }
      const int MAX_EXTENDED_SEARCH_RADIUS = ${MEDIA3_FLOW_EXTENDED_SEARCH_RADIUS};
      const float SUBPIXEL_MIN_CURVATURE = ${MEDIA3_FLOW_SUBPIXEL_MIN_CURVATURE};
      float subpixelOffset(float minusCost, float centerCost, float plusCost) {
        if (centerCost > minusCost || centerCost > plusCost) return 0.0;
        float curvature = minusCost - 2.0 * centerCost + plusCost;
        if (curvature <= SUBPIXEL_MIN_CURVATURE) return 0.0;
        return clamp(0.5 * (minusCost - plusCost) / curvature, -0.5, 0.5);
      }
      float refineSubpixelAxis(ivec2 origin, ivec2 bestOffset, ivec2 axis, int radius, ivec2 limit, float centerCost) {
        ivec2 minusOffset = bestOffset - axis;
        ivec2 plusOffset = bestOffset + axis;
        if (any(greaterThan(abs(minusOffset), ivec2(radius))) ||
            any(greaterThan(abs(plusOffset), ivec2(radius))) ||
            !offsetIsValid(origin, minusOffset, limit) || !offsetIsValid(origin, plusOffset, limit)) return 0.0;
        float minusCost = blockSad(origin, minusOffset, 1);
        float plusCost = blockSad(origin, plusOffset, 1);
        return subpixelOffset(minusCost, centerCost, plusCost);
      }
      void main() {
        ivec2 cell = ivec2(gl_GlobalInvocationID.xy);
        ivec2 grid = imageSize(uFlow);
        if (any(greaterThanEqual(cell, grid))) return;
        ivec2 limit = max(uSize - ivec2(uBlock), ivec2(0));
        ivec2 origin = min(cell * uStep, limit);
        int extendedRadius = min(uCoarseStep * 2, MAX_EXTENDED_SEARCH_RADIUS);
        float coarseBest = 1e20;
        ivec2 coarseOffset = ivec2(0);
        // Preserve the baseline's 25 coarse candidates while covering twice the displacement range.
        for (int dy = -extendedRadius; dy <= extendedRadius; dy += uCoarseStep) {
          for (int dx = -extendedRadius; dx <= extendedRadius; dx += uCoarseStep) {
            ivec2 offset = ivec2(dx, dy);
            if (!offsetIsValid(origin, offset, limit)) continue;
            float cost = blockSad(origin, offset, 2);
            if (cost < coarseBest) { coarseBest = cost; coarseOffset = offset; }
          }
        }
        // Use the coarse winner as a predictor, refine at half the offset spacing, then do the full-SAD 3x3 fit.
        int refinementRadius = max(uCoarseStep / 2, 1);
        float refinementBest = 1e20;
        ivec2 refinedCoarseOffset = coarseOffset;
        for (int dy = coarseOffset.y - refinementRadius; dy <= coarseOffset.y + refinementRadius; dy += 2) {
          if (abs(dy) > extendedRadius) continue;
          for (int dx = coarseOffset.x - refinementRadius; dx <= coarseOffset.x + refinementRadius; dx += 2) {
            if (abs(dx) > extendedRadius) continue;
            ivec2 offset = ivec2(dx, dy);
            if (!offsetIsValid(origin, offset, limit)) continue;
            float refinementCost = blockSad(origin, offset, 2);
            if (refinementCost < refinementBest) {
              refinementBest = refinementCost;
              refinedCoarseOffset = offset;
            }
          }
        }
        float best = 1e20;
        ivec2 bestOffset = refinedCoarseOffset;
        for (int dy = refinedCoarseOffset.y - 1; dy <= refinedCoarseOffset.y + 1; dy++) {
          if (abs(dy) > extendedRadius) continue;
          for (int dx = refinedCoarseOffset.x - 1; dx <= refinedCoarseOffset.x + 1; dx++) {
            if (abs(dx) > extendedRadius) continue;
            ivec2 offset = ivec2(dx, dy);
            if (!offsetIsValid(origin, offset, limit)) continue;
            float cost = blockSad(origin, offset, 1);
            if (cost < best) { best = cost; bestOffset = offset; }
          }
        }
        vec2 refinedOffset = vec2(bestOffset);
        refinedOffset.x += refineSubpixelAxis(origin, bestOffset, ivec2(1, 0), extendedRadius, limit, best);
        refinedOffset.y += refineSubpixelAxis(origin, bestOffset, ivec2(0, 1), extendedRadius, limit, best);
        imageStore(uFlow, cell, vec4(refinedOffset, best, 1.0));
      }
    """

    internal const val SYNTH_COMPUTE_SHADER = """
      #version 310 es
      layout(local_size_x = 8, local_size_y = 8) in;
      precision highp float;
      layout(binding = 0) uniform sampler2D uFrame0;
      layout(binding = 1) uniform sampler2D uFrame1;
      layout(rgba16f, binding = 0) readonly uniform highp image2D uForward;
      layout(rgba16f, binding = 1) readonly uniform highp image2D uBackward;
      layout(rgba8, binding = 2) writeonly uniform highp image2D uOutput;
      uniform ivec2 uSize;
      uniform ivec2 uMotionSize;
      uniform ivec2 uGrid;
      uniform int uStep;
      uniform float uGridAnchorOffset;
      uniform float uAlpha;
      uniform int uCoverageEnabled;
      layout(std430, binding = 3) writeonly buffer FlowCoverageBuffer { uvec4 coverage[]; };
      shared uint coverageClass[64];
      shared uint interframeChangedClass[64];
      // Preserve the visually clean baseline calibration; bidirectional confidence and
      // the bounds/edge guards below remain stricter than that baseline.
      const float CYCLE_ERROR_START = 1.0;
      const float CYCLE_ERROR_END = 6.0;
      const float MATCH_ERROR_START = 0.04;
      const float MATCH_ERROR_END = 0.35;
      const float VISIBILITY_CONFIDENCE_START = ${MEDIA3_FLOW_VISIBILITY_CONFIDENCE_START};
      const float VISIBILITY_CONFIDENCE_END = ${MEDIA3_FLOW_VISIBILITY_CONFIDENCE_END};
      const float STATIC_VECTOR_PROBE_THRESHOLD = 0.5;
      const float INTERFRAME_CHANGE_DIAGNOSTIC_LUMA_THRESHOLD = ${INTERFRAME_CHANGE_DIAGNOSTIC_LUMA_THRESHOLD};
      const float STATIC_BLEND_LUMA_DELTA_THRESHOLD = ${STATIC_BLEND_LUMA_DELTA_THRESHOLD};
      const float FLOW_EDGE_START_SQUARED = 9.0;
      vec4 flowImageAt(ivec2 p, int direction) {
        return direction == 0 ? imageLoad(uForward, p) : imageLoad(uBackward, p);
      }
      vec4 flowAt(vec2 p, int direction) {
        vec2 gridPos = (p - vec2(uGridAnchorOffset)) / float(uStep);
        gridPos = clamp(gridPos, vec2(0.0), vec2(uGrid - ivec2(1)));
        ivec2 a = clamp(ivec2(floor(gridPos)), ivec2(0), uGrid - 1);
        ivec2 b = min(a + ivec2(1), uGrid - 1);
        vec2 t = fract(gridPos);
        vec4 flow00 = flowImageAt(ivec2(a.x, a.y), direction);
        vec4 flow10 = flowImageAt(ivec2(b.x, a.y), direction);
        vec4 flow01 = flowImageAt(ivec2(a.x, b.y), direction);
        vec4 flow11 = flowImageAt(ivec2(b.x, b.y), direction);
        vec4 top = mix(flow00, flow10, t.x);
        vec4 bottom = mix(flow01, flow11, t.x);
        vec4 interpolated = mix(top, bottom, t.y);
        float horizontalTop = dot(flow10.xy - flow00.xy, flow10.xy - flow00.xy);
        float horizontalBottom = dot(flow11.xy - flow01.xy, flow11.xy - flow01.xy);
        float verticalLeft = dot(flow01.xy - flow00.xy, flow01.xy - flow00.xy);
        float verticalRight = dot(flow11.xy - flow10.xy, flow11.xy - flow10.xy);
        float localMotionDisagreementSq = max(
          max(horizontalTop, horizontalBottom),
          max(verticalLeft, verticalRight)
        );
        // Do not average foreground and background vectors across a motion boundary.
        if (localMotionDisagreementSq > FLOW_EDGE_START_SQUARED) {
          ivec2 nearestCell = ivec2(t.x < 0.5 ? a.x : b.x, t.y < 0.5 ? a.y : b.y);
          vec4 nearestFlow = flowImageAt(nearestCell, direction);
          interpolated.xy = nearestFlow.xy;
          // Keep edge fallback decisions tied to measured match and cycle error, not a synthetic penalty.
          interpolated.z = nearestFlow.z;
          interpolated.w = nearestFlow.w;
        }
        return interpolated;
      }
      float inBounds(vec2 uv) {
        return step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
      }
      float flowReliability(vec4 flow, float cycleError, float valid) {
        float consistency = 1.0 - smoothstep(CYCLE_ERROR_START, CYCLE_ERROR_END, cycleError);
        float matchQuality = 1.0 - smoothstep(MATCH_ERROR_START, MATCH_ERROR_END, flow.z);
        return consistency * matchQuality * valid;
      }
      // Algebraic bidirectional target-time initialization; no learned residual or visibility net.
      vec2 flowToFrame0(float t, vec2 forward, vec2 backward) {
        float oneMinusT = 1.0 - t;
        return -oneMinusT * t * forward + t * t * backward;
      }
      vec2 flowToFrame1(float t, vec2 forward, vec2 backward) {
        float oneMinusT = 1.0 - t;
        return oneMinusT * oneMinusT * forward - t * oneMinusT * backward;
      }
      void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        uint pixelClass = 0u;
        uint staticVectorClass = 0u;
        uint changedPixelClass = 0u;
        if (all(lessThan(p, uSize))) {
          vec2 point = vec2(p) + vec2(0.5);
          vec2 uv = point / vec2(uSize);
          vec2 motionPoint = point * vec2(uMotionSize) / vec2(uSize);
          vec4 source0 = texture(uFrame0, uv);
          vec4 source1 = texture(uFrame1, uv);
          float staticChange = dot(abs(source0.rgb - source1.rgb), vec3(0.2126, 0.7152, 0.0722));
          if (staticChange < STATIC_BLEND_LUMA_DELTA_THRESHOLD) {
            imageStore(uOutput, p, mix(source0, source1, uAlpha));
            pixelClass = 3u;
            // Sample the existing vectors at one pixel per workgroup; this probe never writes color.
            if (uCoverageEnabled != 0 && gl_LocalInvocationIndex == 0u) {
              vec4 staticForward = flowAt(motionPoint, 0);
              vec4 staticBackward = flowAt(motionPoint, 1);
              float staticCycleError = length(staticForward.xy + staticBackward.xy);
              float staticConfidence = min(
                flowReliability(staticForward, staticCycleError, 1.0),
                flowReliability(staticBackward, staticCycleError, 1.0)
              );
              float staticVectorMagnitude = max(length(staticForward.xy), length(staticBackward.xy));
              if (staticVectorMagnitude <= STATIC_VECTOR_PROBE_THRESHOLD) {
                staticVectorClass = staticConfidence >= 0.15 ? 3u : 4u;
              } else if (staticConfidence >= 0.15) {
                staticVectorClass = 1u;
              } else {
                staticVectorClass = 2u;
              }
            }
          } else {
            vec4 forwardAtMid = flowAt(motionPoint, 0);
            vec4 backwardAtMid = flowAt(motionPoint, 1);
            vec2 source0Point = motionPoint + flowToFrame0(uAlpha, forwardAtMid.xy, backwardAtMid.xy);
            vec2 source1Point = motionPoint + flowToFrame1(uAlpha, forwardAtMid.xy, backwardAtMid.xy);
            vec4 forwardAtSource = flowAt(source0Point, 0);
            vec4 backwardAtTarget = flowAt(source1Point, 1);
            source0Point = motionPoint + flowToFrame0(uAlpha, forwardAtSource.xy, backwardAtTarget.xy);
            source1Point = motionPoint + flowToFrame1(uAlpha, forwardAtSource.xy, backwardAtTarget.xy);
            vec2 cyclePoint0 = source0Point + forwardAtSource.xy;
            vec2 cyclePoint1 = source1Point + backwardAtTarget.xy;
            vec4 backwardAtForwardEndpoint = flowAt(cyclePoint0, 1);
            vec4 forwardAtBackwardEndpoint = flowAt(cyclePoint1, 0);
            float cycleError0 = length(forwardAtSource.xy + backwardAtForwardEndpoint.xy);
            float cycleError1 = length(backwardAtTarget.xy + forwardAtBackwardEndpoint.xy);
            vec2 uv0Raw = uv + (source0Point - motionPoint) / vec2(uMotionSize);
            vec2 uv1Raw = uv + (source1Point - motionPoint) / vec2(uMotionSize);
            float valid0 = inBounds(uv0Raw);
            float valid1 = inBounds(uv1Raw);
            valid0 *= inBounds(cyclePoint0 / vec2(uMotionSize));
            valid1 *= inBounds(cyclePoint1 / vec2(uMotionSize));
            vec2 uv0 = clamp(uv0Raw, vec2(0.0), vec2(1.0));
            vec2 uv1 = clamp(uv1Raw, vec2(0.0), vec2(1.0));
            vec4 c0 = texture(uFrame0, uv0);
            vec4 c1 = texture(uFrame1, uv1);
            forwardAtSource.z = max(forwardAtSource.z, forwardAtMid.z);
            backwardAtTarget.z = max(backwardAtTarget.z, backwardAtMid.z);
            float confidence0 = flowReliability(forwardAtSource, cycleError0, valid0);
            float confidence1 = flowReliability(backwardAtTarget, cycleError1, valid1);
            // Treat each endpoint as a candidate visibility source: equal confidence preserves alpha,
            // while a disoccluded or out-of-bounds endpoint yields to its valid counterpart.
            float confidence = max(confidence0, confidence1);
            if (confidence < VISIBILITY_CONFIDENCE_START) {
              imageStore(uOutput, p, uAlpha < 0.5 ? source0 : source1);
              pixelClass = 2u;
            } else {
              float visibility0 = smoothstep(VISIBILITY_CONFIDENCE_START, VISIBILITY_CONFIDENCE_END, confidence0);
              float visibility1 = smoothstep(VISIBILITY_CONFIDENCE_START, VISIBILITY_CONFIDENCE_END, confidence1);
              float weight0 = (1.0 - uAlpha) * visibility0;
              float weight1 = uAlpha * visibility1;
              float weightSum = weight0 + weight1;
              if (weightSum <= 0.0001) {
                imageStore(uOutput, p, uAlpha < 0.5 ? source0 : source1);
                pixelClass = 2u;
              } else {
                imageStore(uOutput, p, mix(c0, c1, weight1 / weightSum));
                pixelClass = 1u;
              }
            }
          }
          if (uCoverageEnabled != 0 && staticChange >= INTERFRAME_CHANGE_DIAGNOSTIC_LUMA_THRESHOLD) {
            changedPixelClass = pixelClass;
          }
        }
        if (uCoverageEnabled != 0) {
          coverageClass[gl_LocalInvocationIndex] = pixelClass;
          interframeChangedClass[gl_LocalInvocationIndex] = changedPixelClass;
          barrier();
          if (gl_LocalInvocationIndex == 0u) {
            uvec4 counts = uvec4(0u);
            uvec4 changedCounts = uvec4(0u);
            for (uint index = 0u; index < 64u; index++) {
              if (coverageClass[index] == 1u) counts.x++;
              else if (coverageClass[index] == 2u) counts.y++;
              else if (coverageClass[index] == 3u) counts.z++;
              if (interframeChangedClass[index] == 1u) changedCounts.x++;
              else if (interframeChangedClass[index] == 2u) changedCounts.y++;
              else if (interframeChangedClass[index] == 3u) changedCounts.z++;
              if (interframeChangedClass[index] != 0u) changedCounts.w++;
            }
            uint groupIndex = gl_WorkGroupID.y * gl_NumWorkGroups.x + gl_WorkGroupID.x;
            counts.w = staticVectorClass;
            coverage[groupIndex * 2u] = counts;
            coverage[groupIndex * 2u + 1u] = changedCounts;
          }
        }
      }
    """
  }

  @androidx.annotation.RequiresApi(30)
  private object Api30 {
    @JvmStatic
    fun setFrameRate(surface: Surface, frameRate: Float) {
      val compatibility = if (frameRate == 0f) {
        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
      } else {
        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
      }
      surface.setFrameRate(frameRate, compatibility)
    }
  }

  @androidx.annotation.RequiresApi(31)
  private object Api31 {
    @JvmStatic
    fun setFrameRate(surface: Surface, frameRate: Float, changeFrameRateStrategy: Int) {
      val compatibility = if (frameRate == 0f) {
        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
      } else {
        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
      }
      surface.setFrameRate(frameRate, compatibility, changeFrameRateStrategy)
    }

    @JvmStatic
    fun frameRateSurfacePolicy(context: Context): FlowFrameRateSurfacePolicy {
      val preference = runCatching {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
          ?: return@runCatching DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN
        displayManager.getMatchContentFrameRateUserPreference()
      }.getOrDefault(DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN)
      val preferenceLabel = when (preference) {
        DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS -> "always"
        DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER -> "never"
        DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY -> "seamless_only"
        else -> "unknown:$preference"
      }
      val changeStrategy = Media3FlowFrameRatePolicy.surfaceChangeStrategy(preference)
      return FlowFrameRateSurfacePolicy(
        userPreferenceLabel = preferenceLabel,
        changeStrategy = changeStrategy,
        strategyLabel = if (preference == DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS) {
          "always"
        } else {
          "only_if_seamless"
        },
      )
    }
  }

  private fun displayRefreshRate(context: Context): Float = runCatching {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager.defaultDisplay.refreshRate
  }.getOrDefault(0f)
}
