package app.infinity.mpvz.ui.player

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
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
  val outputFps: Float = 0f,
  val generatedFps: Float = 0f,
  val targetFps: Int = 0,
  val generatedFrames: Long = 0L,
  val droppedFrames: Long = 0L,
  val skippedFrames: Long = 0L,
  /** CPU time spent submitting motion-estimation commands, not a GPU-completion timer. */
  val motionEstimateSubmitMs: Float = 0f,
  /** Null until a non-blocking GPU confidence readback is available. */
  val confidence: Float? = null,
  val motionGridWidth: Int = 0,
  val motionGridHeight: Int = 0,
  /** Reduced luma/motion dimensions; synthesized output remains source-resolution. */
  val processingWidth: Int = 0,
  val processingHeight: Int = 0,
  val state: String = "off",
  val bypassReason: String? = null,
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
  private val maxDimension = effectiveMpvFlowMaxDimension(targetFps).coerceIn(160, 480)
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
  private val generatedWallTimes = ArrayDeque<Long>()
  private val renderTaskPending = AtomicBoolean(false)
  private var choreographer: Choreographer? = null
  private var frameCallbackScheduled = false
  private val displayFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
    frameCallbackScheduled = false
    if (!disposed && running) {
      runCatching { renderAtDisplayFrame(frameTimeNanos) }
        .onFailure { reportError(it) }
      scheduleDisplayFrame()
    }
  }
  private val fallbackFrameRunnable = Runnable {
    frameCallbackScheduled = false
    if (!disposed && running) {
      runCatching { renderAtDisplayFrame(null) }
        .onFailure { reportError(it) }
      scheduleDisplayFrame()
    }
  }
  private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(6 * 2 * Float.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
    .apply { position(0) }

  @Volatile private var currentFormat: Format? = null
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
  private var skippedFrames = 0L
  private var lastMotionSubmitMs = 0f
  private var lastMetricsAtNs = 0L
  private var lastTimingLogAtNs = 0L
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

  private data class PendingInput(val ptsUs: Long, val generation: Long, val handler: VideoSink.VideoFrameHandler)
  private data class FrameToken(val ptsUs: Long, val generation: Long, val releaseTimestampNs: Long)
  private data class PlaybackClock(val positionUs: Long, val elapsedRealtimeUs: Long)

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
      pendingInput.addLast(PendingInput(ptsUs, streamGeneration, videoFrameHandler))
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
    if (!renderTaskPending.compareAndSet(false, true)) return
    val accepted = glHandler.post {
      try {
        if (!disposed && initialized) renderAtDisplayFrame(null)
      } catch (error: RuntimeException) {
        reportError(error)
      } finally {
        renderTaskPending.set(false)
      }
    }
    if (!accepted) renderTaskPending.set(false)
  }

  /** Schedules one output at each display vsync, rather than assuming Media3's work loop is 120 Hz. */
  private fun scheduleDisplayFrame() {
    if (!running || disposed || !outputAvailable || sourceFrames.isEmpty() || frameCallbackScheduled) return
    val currentChoreographer = choreographer ?: runCatching { Choreographer.getInstance() }
      .onFailure { Log.w(logTag, "Choreographer unavailable; using bounded timer pacing", it) }
      .getOrNull()
      ?.also { choreographer = it }
    frameCallbackScheduled = true
    if (currentChoreographer != null) {
      currentChoreographer.postFrameCallback(displayFrameCallback)
    } else {
      val delayMs = (1_000f / targetFps).roundToLong().coerceAtLeast(1L)
      if (!glHandler.postDelayed(fallbackFrameRunnable, delayMs)) frameCallbackScheduled = false
    }
  }

  private fun cancelDisplayFrame() {
    if (frameCallbackScheduled) choreographer?.removeFrameCallback(displayFrameCallback)
    glHandler.removeCallbacks(fallbackFrameRunnable)
    frameCallbackScheduled = false
  }

  private fun renderAtDisplayFrame(frameTimeNanos: Long?) {
    if (!initialized || disposed || !outputAvailable) return
    if (!ensureEgl() || !makePbufferCurrent()) return
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
      if (inputInFlight || pendingInput.isEmpty() || disposed) return
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
    try {
      // Handler.render is called on Media3's playback thread; GPU work stays on the dedicated GL thread.
      pending.handler.render(token.releaseTimestampNs)
    } catch (error: RuntimeException) {
      synchronized(inputLock) {
        if (expectedTexturePts.isNotEmpty()) expectedTexturePts.removeLast()
        inputInFlight = false
      }
      droppedFrames.incrementAndGet()
      dispatchListener { it.onFrameDropped() }
      reportError(error)
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
        inputInFlight = false
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

  private fun analyzeNewestPair() {
    if (!flowAvailable || sourceFrames.size < 2) {
      lastBypassReason = "gpu_flow_unavailable"
      return
    }
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
    val startNs = System.nanoTime()
    try {
      GLES31.glUseProgram(flowProgram)
      checkGlError("motion glUseProgram", frameProbeOnly = true)
      val sizeLocation = GLES31.glGetUniformLocation(flowProgram, "uSize")
      checkGlError("motion lookup uSize", frameProbeOnly = true)
      GLES31.glUniform2i(sizeLocation, processingWidth, processingHeight)
      checkGlError("motion uniform uSize", frameProbeOnly = true)
      val blockLocation = GLES31.glGetUniformLocation(flowProgram, "uBlock")
      checkGlError("motion lookup uBlock", frameProbeOnly = true)
      GLES31.glUniform1i(blockLocation, FLOW_BLOCK_SIZE)
      checkGlError("motion uniform uBlock", frameProbeOnly = true)
      val stepLocation = GLES31.glGetUniformLocation(flowProgram, "uStep")
      checkGlError("motion lookup uStep", frameProbeOnly = true)
      GLES31.glUniform1i(stepLocation, FLOW_GRID_STEP)
      checkGlError("motion uniform uStep", frameProbeOnly = true)
      val radiusLocation = GLES31.glGetUniformLocation(flowProgram, "uRadius")
      checkGlError("motion lookup uRadius", frameProbeOnly = true)
      GLES31.glUniform1i(radiusLocation, FLOW_SEARCH_RADIUS)
      checkGlError("motion uniform uRadius", frameProbeOnly = true)
      GLES31.glBindImageTexture(0, frame0.lumaTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)
      checkGlError("forward motion bind input 0", frameProbeOnly = true)
      GLES31.glBindImageTexture(1, frame1.lumaTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)
      checkGlError("forward motion bind input 1", frameProbeOnly = true)
      GLES31.glBindImageTexture(2, reusable.forwardTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
      checkGlError("forward motion bind output", frameProbeOnly = true)
      GLES31.glDispatchCompute(ceil(gridWidth / 8.0).toInt(), ceil(gridHeight / 8.0).toInt(), 1)
      checkGlError("forward motion dispatch", frameProbeOnly = true)
      GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
      checkGlError("forward motion barrier", frameProbeOnly = true)

      GLES31.glBindImageTexture(0, frame1.lumaTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)
      checkGlError("backward motion bind input 0", frameProbeOnly = true)
      GLES31.glBindImageTexture(1, frame0.lumaTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)
      checkGlError("backward motion bind input 1", frameProbeOnly = true)
      GLES31.glBindImageTexture(2, reusable.backwardTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA16F)
      checkGlError("backward motion bind output", frameProbeOnly = true)
      GLES31.glDispatchCompute(ceil(gridWidth / 8.0).toInt(), ceil(gridHeight / 8.0).toInt(), 1)
      checkGlError("backward motion dispatch", frameProbeOnly = true)
      GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
      checkGlError("backward motion barrier", frameProbeOnly = true)
      checkGlError("motion estimate")
      reusable.frame0PtsUs = frame0.ptsUs
      reusable.frame1PtsUs = frame1.ptsUs
      reusable.available = true
      lastMotionSubmitMs = (System.nanoTime() - startNs) / 1_000_000f
      lastBypassReason = null
      lastState = "motion_estimated"
    } catch (error: RuntimeException) {
      reusable.available = false
      flowAvailable = false
      lastMotionSubmitMs = (System.nanoTime() - startNs) / 1_000_000f
      lastBypassReason = "motion_pass_error"
      lastState = "source_fallback"
      Log.w(logTag, "Motion pass failed; disabling GPU interpolation for this stream and retaining source frames", error)
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
        lastBypassReason = "output_deadline_missed"
      }
    }
    val bracket = findSourcePair(sourceSnapshot, targetPtsUs)
    val pair = bracket?.let { (a, b) ->
      motionPairs.firstOrNull { it.available && it.frame0PtsUs == a.ptsUs && it.frame1PtsUs == b.ptsUs }
    }
    val deltaUs = bracket?.let { it.second.ptsUs - it.first.ptsUs } ?: 0L
    val synthesize = bracket != null && pair != null &&
      Media3FlowCadence.needsInterpolation(deltaUs, targetFps, speed) &&
      lastMotionSubmitMs < deltaUs / 1000f * ANALYSIS_DEADLINE_RESERVE
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
        generatedFrames++
        generatedWallTimes.addLast(System.nanoTime())
        while (generatedWallTimes.size > OUTPUT_RATE_WINDOW) generatedWallTimes.removeFirst()
      }
      lastRenderedPtsUs = targetPtsUs
      outputWallTimes.addLast(System.nanoTime())
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
    GLES31.glUseProgram(synthProgram)
    bindTextureUnit(0, a.colorTexture)
    bindTextureUnit(1, b.colorTexture)
    GLES31.glUniform1i(GLES31.glGetUniformLocation(synthProgram, "uFrame0"), 0)
    GLES31.glUniform1i(GLES31.glGetUniformLocation(synthProgram, "uFrame1"), 1)
    GLES31.glUniform2i(GLES31.glGetUniformLocation(synthProgram, "uSize"), frameWidth, frameHeight)
    GLES31.glUniform2i(GLES31.glGetUniformLocation(synthProgram, "uMotionSize"), processingWidth, processingHeight)
    GLES31.glUniform2i(GLES31.glGetUniformLocation(synthProgram, "uGrid"), gridWidth, gridHeight)
    GLES31.glUniform1i(GLES31.glGetUniformLocation(synthProgram, "uStep"), FLOW_GRID_STEP)
    GLES31.glUniform1f(GLES31.glGetUniformLocation(synthProgram, "uAlpha"), alpha)
    GLES31.glBindImageTexture(0, pair.forwardTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA16F)
    GLES31.glBindImageTexture(1, pair.backwardTexture, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA16F)
    GLES31.glBindImageTexture(2, outputTexture, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
    GLES31.glDispatchCompute(ceil(frameWidth / 8.0).toInt(), ceil(frameHeight / 8.0).toInt(), 1)
    GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
  }

  private fun drawSourceFrame(slot: FrameSlot, presentationTimeNs: Long, ptsUs: Long): Boolean =
    drawTexture(slot.colorTexture, presentationTimeNs, ptsUs)

  private fun drawTexture(textureId: Int, presentationTimeNs: Long, ptsUs: Long): Boolean {
    if (windowSurface == EGL14.EGL_NO_SURFACE || !makeWindowCurrent()) return false
    val width = outputResolution.width.takeIf { it > 0 } ?: frameWidth
    val height = outputResolution.height.takeIf { it > 0 } ?: frameHeight
    GLES20.glViewport(0, 0, width, height)
    GLES20.glUseProgram(blitProgram)
    bindTextureUnit(0, textureId)
    GLES20.glUniform1i(GLES20.glGetUniformLocation(blitProgram, "uImage"), 0)
    drawFullscreenTriangle()
    EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, presentationTimeNs.coerceAtLeast(0L))
    val swapped = EGL14.eglSwapBuffers(eglDisplay, windowSurface)
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
    GLES31.glDispatchCompute(ceil(processingWidth / 8.0).toInt(), ceil(processingHeight / 8.0).toInt(), 1)
    checkGlError("luma dispatch", frameProbeOnly = true)
    GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
    checkGlError("luma barrier", frameProbeOnly = true)
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
    gridWidth = ceil(width.toDouble() / FLOW_GRID_STEP).toInt()
    gridHeight = ceil(height.toDouble() / FLOW_GRID_STEP).toInt()
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
    flowAvailable = lumaProgram != 0 && flowProgram != 0 && synthProgram != 0
    if (!flowAvailable) lastBypassReason = "compute_shader_unavailable"
    setOutputFrameRate()
    publishDiagnostics(if (flowAvailable) "gpu_ready" else "source_fallback", lastBypassReason, force = true)
  }

  private fun clearSourceFrames() {
    sourceFrames.forEach { it.inUse = false; it.ptsUs = C.TIME_UNSET }
    sourceFrames.clear()
    motionPairs.forEach { it.available = false; it.frame0PtsUs = C.TIME_UNSET; it.frame1PtsUs = C.TIME_UNSET }
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
      eglInfoLogged = true
    }
    if (!graphicsProgramsCompiled) {
      compileGraphicsPrograms()
      graphicsProgramsCompiled = true
    }
    if (!preflightOnly && !computeProgramsCompiled) {
      compileComputePrograms()
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
    intArrayOf(copyProgram, blitProgram, lumaProgram, flowProgram, synthProgram)
      .filter { it != 0 }
      .forEach { GLES20.glDeleteProgram(it) }
    copyProgram = 0
    blitProgram = 0
    lumaProgram = 0
    flowProgram = 0
    synthProgram = 0
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
      runCatching { Api30.setFrameRate(surface, frameRate) }
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
      skippedFrames = skippedFrames,
      motionEstimateSubmitMs = lastMotionSubmitMs,
      confidence = null,
      motionGridWidth = gridWidth,
      motionGridHeight = gridHeight,
      processingWidth = processingWidth,
      processingHeight = processingHeight,
      state = state,
      bypassReason = reason,
    )
    runCatching { onDiagnostics(diagnostics) }
  }

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
    private const val FLOW_BLOCK_SIZE = 8
    private const val FLOW_GRID_STEP = 6
    private const val FLOW_SEARCH_RADIUS = 4
    private const val HISTORY_SIZE = 24
    private const val OUTPUT_RATE_WINDOW = 31
    private const val ANALYSIS_DEADLINE_RESERVE = 0.85f
    private const val METRICS_INTERVAL_NS = 500_000_000L
    private const val GL_INIT_TIMEOUT_MS = 5_000L
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
      void main() { outColor = texture(uImage, vUv); }
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

    private const val FLOW_COMPUTE_SHADER = """
      #version 310 es
      layout(local_size_x = 8, local_size_y = 8) in;
      precision highp float;
      layout(rgba8, binding = 0) readonly uniform highp image2D uSource;
      layout(rgba8, binding = 1) readonly uniform highp image2D uTarget;
      layout(rgba16f, binding = 2) writeonly uniform highp image2D uFlow;
      uniform ivec2 uSize;
      uniform int uBlock;
      uniform int uStep;
      uniform int uRadius;
      void main() {
        ivec2 cell = ivec2(gl_GlobalInvocationID.xy);
        ivec2 grid = imageSize(uFlow);
        if (any(greaterThanEqual(cell, grid))) return;
        ivec2 limit = max(uSize - ivec2(uBlock), ivec2(0));
        ivec2 origin = min(cell * uStep, limit);
        float coarseBest = 1e20;
        ivec2 coarseOffset = ivec2(0);
        for (int dy = -8; dy <= 8; dy += 2) {
          if (abs(dy) > uRadius) continue;
          for (int dx = -8; dx <= 8; dx += 2) {
            if (abs(dx) > uRadius) continue;
            ivec2 offset = ivec2(dx, dy);
            ivec2 targetOrigin = origin + offset;
            if (any(lessThan(targetOrigin, ivec2(0))) || any(greaterThan(targetOrigin, limit))) continue;
            float sad = 0.0;
            for (int y = 0; y < 8; y += 2) {
              for (int x = 0; x < 8; x += 2) {
                ivec2 p = origin + ivec2(x, y);
                sad += abs(imageLoad(uSource, p).r - imageLoad(uTarget, p + offset).r);
              }
            }
            if (sad < coarseBest) { coarseBest = sad; coarseOffset = offset; }
          }
        }
        float best = 1e20;
        ivec2 bestOffset = coarseOffset;
        for (int dy = coarseOffset.y - 1; dy <= coarseOffset.y + 1; dy++) {
          if (abs(dy) > uRadius) continue;
          for (int dx = coarseOffset.x - 1; dx <= coarseOffset.x + 1; dx++) {
            if (abs(dx) > uRadius) continue;
            ivec2 offset = ivec2(dx, dy);
            ivec2 targetOrigin = origin + offset;
            if (any(lessThan(targetOrigin, ivec2(0))) || any(greaterThan(targetOrigin, limit))) continue;
            float sad = 0.0;
            for (int y = 0; y < 8; y++) {
              for (int x = 0; x < 8; x++) {
                ivec2 p = origin + ivec2(x, y);
                sad += abs(imageLoad(uSource, p).r - imageLoad(uTarget, p + offset).r);
              }
            }
            if (sad < best) { best = sad; bestOffset = offset; }
          }
        }
        float qualityCost = best / 64.0;
        imageStore(uFlow, cell, vec4(vec2(bestOffset), qualityCost, 1.0));
      }
    """

    private const val SYNTH_COMPUTE_SHADER = """
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
      uniform float uAlpha;
      vec4 flowImageAt(ivec2 p, int direction) {
        return direction == 0 ? imageLoad(uForward, p) : imageLoad(uBackward, p);
      }
      vec4 flowAt(vec2 p, int direction) {
        vec2 gridPos = p / float(uStep);
        ivec2 a = clamp(ivec2(floor(gridPos)), ivec2(0), uGrid - 1);
        ivec2 b = min(a + ivec2(1), uGrid - 1);
        vec2 t = fract(gridPos);
        vec4 top = mix(flowImageAt(ivec2(a.x, a.y), direction), flowImageAt(ivec2(b.x, a.y), direction), t.x);
        vec4 bottom = mix(flowImageAt(ivec2(a.x, b.y), direction), flowImageAt(ivec2(b.x, b.y), direction), t.x);
        return mix(top, bottom, t.y);
      }
      void main() {
        ivec2 p = ivec2(gl_GlobalInvocationID.xy);
        if (any(greaterThanEqual(p, uSize))) return;
        vec2 point = vec2(p) + vec2(0.5);
        vec2 uv = point / vec2(uSize);
        vec2 motionPoint = point * vec2(uMotionSize) / vec2(uSize);
        vec4 source0 = texture(uFrame0, uv);
        vec4 source1 = texture(uFrame1, uv);
        float staticChange = dot(abs(source0.rgb - source1.rgb), vec3(0.2126, 0.7152, 0.0722));
        if (staticChange < 0.018) {
          imageStore(uOutput, p, mix(source0, source1, uAlpha));
          return;
        }
        vec4 f = flowAt(motionPoint, 0);
        vec2 backAtTarget = flowAt(motionPoint + f.xy, 1).xy;
        float consistency = length(f.xy + backAtTarget);
        float confidence = 1.0 - smoothstep(1.0, 6.0, consistency);
        confidence *= 1.0 - smoothstep(0.04, 0.35, f.z);
        vec2 uv0 = clamp(uv - uAlpha * f.xy / vec2(uMotionSize), vec2(0.0), vec2(1.0));
        vec2 uv1 = clamp(uv + (1.0 - uAlpha) * backAtTarget / vec2(uMotionSize), vec2(0.0), vec2(1.0));
        vec4 c0 = texture(uFrame0, uv0);
        vec4 c1 = texture(uFrame1, uv1);
        float weight0 = (1.0 - uAlpha) * confidence;
        float weight1 = uAlpha * confidence;
        if (confidence < 0.15) {
          weight0 = uAlpha < 0.5 ? 1.0 : 0.0;
          weight1 = 1.0 - weight0;
        }
        vec4 color = c0 * weight0 + c1 * weight1;
        float total = max(weight0 + weight1, 1e-4);
        imageStore(uOutput, p, vec4(color.rgb / total, 1.0));
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

  private fun displayRefreshRate(context: Context): Float = runCatching {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager.defaultDisplay.refreshRate
  }.getOrDefault(0f)
}
