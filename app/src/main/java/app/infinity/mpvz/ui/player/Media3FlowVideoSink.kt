package app.infinity.mpvz.ui.player

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Build
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
import app.infinity.mpvz.BuildConfig
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
  private val loggedFlowDiagnosticKeys = mutableSetOf<String>()
  private var firstSuccessfulPairDiagnosticLogged = false
  private var lastLoggedOutputDiagnosticSignature: String? = null

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
  private var framebuffer = 0
  private var graphicsProgramsCompiled = false
  private var frameWidth = 0
  private var frameHeight = 0
  private var processingWidth = 0
  private var processingHeight = 0
  private var gridWidth = 0
  private var gridHeight = 0
  private var outputTexture = 0
  private var flowVulkanContext = 0L
  private var outputHardwareBuffer: HardwareBuffer? = null
  private var outputImageHandle = 0L
  private var flowAvailable = false
  @Volatile private var preflightAvailable = false
  @Volatile var preflightFailureReason: String? = null
    private set

  init {
    logFlowRecordOnce(
      "flow-config",
      Log.INFO,
      "event=config requested_enabled=true renderer=native-media3 capture=gles3 compute_backend=vulkan " +
        "target_fps=$targetFps source_pts=media3_decoder sync_mode=1x hardware_decoder=media3 " +
        "cpu_readback=no",
    )
    val buildSupportsVulkan = BuildConfig.MPV_SUPPORTS_VULKAN
    val apiLevelSupported = Build.VERSION.SDK_INT >= 26
    val vulkanFeatureAvailable = appContext.packageManager.hasSystemFeature("android.hardware.vulkan.version")
    if (!buildSupportsVulkan || !apiLevelSupported || !vulkanFeatureAvailable) {
      logFlowRecordOnce(
        "device-vulkan-capability",
        Log.WARN,
        "event=capability_check state=unavailable stage=device_support_predicate " +
          "build_supports_vulkan=$buildSupportsVulkan sdk_int=${Build.VERSION.SDK_INT} " +
          "required_sdk=26 vulkan_version_feature=$vulkanFeatureAvailable reason=vulkan_compute_unavailable",
      )
    }
    val preflightResult = runCatching {
      runOnGlThreadSync {
        val reason = when {
          !ensureEgl() -> "egl_capture_setup_failed"
          !deviceSupportsVulkanFlow(appContext) -> "vulkan_compute_unavailable"
          else -> {
            flowVulkanContext = Media3FlowVulkanNative.nativeCreateContext()
            when {
              flowVulkanContext == 0L -> "vulkan_context_or_compute_setup_failed"
              !preflightSharedImage(output = false) -> "vulkan_hardwarebuffer_input_preflight_failed"
              !preflightSharedImage(output = true) -> "vulkan_hardwarebuffer_output_preflight_failed"
              else -> null
            }
          }
        }
        if (reason != null && flowVulkanContext != 0L) {
          Media3FlowVulkanNative.nativeDestroyContext(flowVulkanContext)
          flowVulkanContext = 0L
        }
        reason
      }
    }
    preflightFailureReason = preflightResult.getOrElse { error ->
      logFlowRecordOnce(
        "preflight-initialization-exception",
        Log.ERROR,
        "event=preflight state=failed stage=initialization reason=preflight_exception",
        error,
      )
      "preflight_exception"
    }
    preflightAvailable = preflightFailureReason == null
    if (preflightAvailable) {
      logFlowRecordOnce(
        "preflight-ready",
        Log.INFO,
        "event=preflight state=ready backend=vulkan-spirv target_fps=$targetFps " +
          "input_ahb=imported output_ahb=imported source_pts=media3_decoder sync_mode=1x",
      )
    }
    if (!preflightAvailable) {
      if (preflightResult.isFailure && flowVulkanContext != 0L) {
        runCatching {
          runOnGlThreadSync {
            Media3FlowVulkanNative.nativeDestroyContext(flowVulkanContext)
            flowVulkanContext = 0L
          }
        }
      }
      logFlowRecordOnce(
        "preflight-final-failure",
        Log.ERROR,
        "event=preflight state=failed stage=final reason=${preflightFailureReason ?: "unknown"} " +
          "fallback=stock_media3_renderer",
      )
    }
  }

  private fun logFlowRecordOnce(
    key: String,
    priority: Int,
    details: String,
    error: Throwable? = null,
  ) {
    val shouldLog = synchronized(loggedFlowDiagnosticKeys) { loggedFlowDiagnosticKeys.add(key) }
    if (!shouldLog) return
    val message = "MPVFLOW_DIAGNOSTIC component=native-media3 $details"
    when {
      error != null && priority >= Log.ERROR -> Log.e(logTag, message, error)
      error != null -> Log.w(logTag, message, error)
      else -> Log.println(priority, logTag, message)
    }
  }

  fun isPreflightAvailable(): Boolean = preflightAvailable && !disposed

  private fun createSharedHardwareBuffer(width: Int, height: Int, role: String): HardwareBuffer {
    val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
    return try {
      HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1, usage)
    } catch (error: RuntimeException) {
      logFlowRecordOnce(
        "ahb-allocation-$role",
        Log.ERROR,
        "event=ahb_import state=failed stage=AHardwareBuffer.create image_role=$role " +
          "width=$width height=$height format=rgba8 usage=0x${usage.toString(16)}",
        error,
      )
      throw error
    }
  }

  private fun preflightSharedImage(output: Boolean): Boolean {
    val role = if (output) "output" else "input"
    if (flowVulkanContext == 0L) {
      logFlowRecordOnce(
        "preflight-$role-context",
        Log.ERROR,
        "event=preflight state=failed role=$role stage=vulkan_context reason=missing",
      )
      return false
    }
    if (!makePbufferCurrent()) {
      logFlowRecordOnce(
        "preflight-$role-egl-current",
        Log.ERROR,
        "event=preflight state=failed role=$role stage=egl_make_current reason=failed " +
          "egl_error=0x${EGL14.eglGetError().toString(16)}",
      )
      return false
    }
    val buffer = try {
      createSharedHardwareBuffer(16, 16, "$role-preflight")
    } catch (error: RuntimeException) {
      logFlowRecordOnce(
        "preflight-$role-ahb-allocation",
        Log.ERROR,
        "event=preflight state=failed role=$role stage=ahardwarebuffer_allocate reason=exception",
        error,
      )
      return false
    }
    var imageHandle = 0L
    var texture = 0
    return try {
      imageHandle = Media3FlowVulkanNative.nativeCreateFrameImage(
        flowVulkanContext, buffer, 16, 16, output,
      )
      if (imageHandle == 0L) {
        logFlowRecordOnce(
          "preflight-$role-vulkan-import",
          Log.ERROR,
          "event=preflight state=failed role=$role stage=vulkan_ahardwarebuffer_import " +
            "reason=native_import_rejected width=16 height=16 format=rgba8 usage=0x${buffer.usage.toString(16)}",
        )
        return false
      }
      texture = Media3FlowVulkanNative.nativeCreateEglTexture(buffer)
      if (texture == 0) {
        logFlowRecordOnce(
          "preflight-$role-egl-import",
          Log.ERROR,
          "event=preflight state=failed role=$role stage=egl_ahardwarebuffer_import " +
            "reason=native_import_rejected width=16 height=16 format=rgba8 usage=0x${buffer.usage.toString(16)}",
        )
        return false
      }
      val glError = GLES20.glGetError()
      if (glError != GLES20.GL_NO_ERROR) {
        logFlowRecordOnce(
          "preflight-$role-egl-texture-validation",
          Log.ERROR,
          "event=preflight state=failed role=$role stage=egl_texture_validation " +
            "reason=gl_error_0x${glError.toString(16)} texture_id=$texture",
        )
        return false
      }
      logFlowRecordOnce(
        "preflight-$role-ready",
        Log.INFO,
        "event=preflight state=ready role=$role stage=vulkan_and_egl_ahardwarebuffer_import " +
          "width=16 height=16 format=rgba8 usage=0x${buffer.usage.toString(16)} " +
          "native_image_handle_present=true egl_texture_id=$texture",
      )
      true
    } catch (error: RuntimeException) {
      logFlowRecordOnce(
        "preflight-$role-exception",
        Log.ERROR,
        "event=preflight state=failed role=$role stage=hardwarebuffer_import reason=exception",
        error,
      )
      false
    } finally {
      if (texture != 0) deleteTexture(texture)
      if (imageHandle != 0L) {
        Media3FlowVulkanNative.nativeDestroyImage(flowVulkanContext, imageHandle)
      }
      buffer.close()
    }
  }

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

  private class FrameSlot {
    var hardwareBuffer: HardwareBuffer? = null
    var nativeImageHandle = 0L
    var colorTexture = 0
    var ptsUs = C.TIME_UNSET
    var inUse = false
  }

  private class MotionPairSlot {
    var nativePairHandle = 0L
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
    check(ensureEgl()) { "No GLES 3.0 capture/presentation context for Media3 Flow" }
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
      logFlowRecordOnce(
        "input-frame-capture-failure",
        Log.ERROR,
        "event=input_frame state=failed stage=onInputFrameAvailable fallback=drop_frame " +
          "source_pts_us=${lastInputPtsUs.takeIf { it != C.TIME_UNSET } ?: "unknown"}",
        error,
      )
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
        prepareSharedFrame(slot)
      } catch (error: RuntimeException) {
        flowAvailable = false
        lastBypassReason = "vulkan_frame_prepare_error"
        lastState = "source_fallback"
        logFlowRecordOnce(
          "vulkan-frame-prepare-failure",
          Log.ERROR,
          "event=prepare_frame state=failed stage=prepareSharedFrame source_pts_us=$ptsUs " +
            "target_fps=$targetFps fallback=original_media3_source_frames",
          error,
        )
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
      .forEach {
        releaseMotionPair(it)
        it.frame0PtsUs = C.TIME_UNSET
        it.frame1PtsUs = C.TIME_UNSET
      }
  }

  private fun initializeGpuTimerQueries() {
    if (gpuTimerQueryIds.isNotEmpty()) return
    val extensionCount = IntArray(1)
    GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, extensionCount, 0)
    val hasTimerExtension = (0 until extensionCount[0]).any { index ->
      GLES30.glGetStringi(GLES20.GL_EXTENSIONS, index) == "GL_EXT_disjoint_timer_query"
    }
    if (!hasTimerExtension) {
      Log.i(logTag, "Presentation GPU timer queries unavailable; Vulkan flow GPU duration is not sampled")
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

  private fun pixelCoverageSnapshot(): FlowPixelCoverageStats = FlowPixelCoverageStats()

  private fun analyzeNewestPair() {
    if (!flowAvailable || sourceFrames.size < 2) {
      lastBypassReason = "vulkan_flow_unavailable"
      return
    }
    val frame0 = sourceFrames.elementAt(sourceFrames.size - 2)
    val frame1 = sourceFrames.last()
    val deltaUs = frame1.ptsUs - frame0.ptsUs
    if (deltaUs <= 0L || !Media3FlowCadence.needsInterpolation(deltaUs, targetFps, playbackSpeed)) {
      lastBypassReason = if (deltaUs <= 0L) "invalid_source_delta" else "source_meets_target"
      return
    }
    if (motionPairs.any {
        it.available && it.frame0PtsUs == frame0.ptsUs && it.frame1PtsUs == frame1.ptsUs
      }
    ) return
    val reusable = motionPairs.firstOrNull {
      !it.available || retentionBoundaryUs(latestPositionUs) >= it.frame1PtsUs
    }
    if (reusable == null) {
      lastBypassReason = "motion_pair_queue_full"
      return
    }
    releaseMotionPair(reusable)
    reusable.frame0PtsUs = frame0.ptsUs
    reusable.frame1PtsUs = frame1.ptsUs
    reusable.motionEstimateGpuMs = null
    reusable.motionForwardGpuMs = null
    reusable.motionBackwardGpuMs = null
    val startNs = System.nanoTime()
    val nativePair = runCatching {
      Media3FlowVulkanNative.nativeAnalyzePair(
        flowVulkanContext, frame0.nativeImageHandle, frame1.nativeImageHandle,
      )
    }.getOrDefault(0L)
    lastMotionSubmitMs = (System.nanoTime() - startNs) / 1_000_000f
    reusable.motionEstimateSubmitMs = lastMotionSubmitMs
    if (nativePair == 0L) {
      reusable.available = false
      flowAvailable = false
      lastBypassReason = "vulkan_motion_analysis_error"
      lastState = "source_fallback"
      logFlowRecordOnce(
        "first-pair-analysis-failure",
        Log.ERROR,
        "event=source_pair state=failed backend=vulkan-spirv stage=nativeAnalyzePair " +
          "source_pts0_us=${frame0.ptsUs} source_pts1_us=${frame1.ptsUs} source_delta_us=$deltaUs " +
          "target_fps=$targetFps playback_speed=${formatLogFloat(playbackSpeed)} " +
          "reason=vulkan_motion_analysis_error fallback=original_media3_source_frames",
      )
      return
    }
    reusable.nativePairHandle = nativePair
    reusable.available = true
    lastMotionGpuMs = null
    lastBypassReason = null
    lastState = "motion_estimated_vulkan"
    if (!firstSuccessfulPairDiagnosticLogged) {
      firstSuccessfulPairDiagnosticLogged = true
      logFlowRecordOnce(
        "first-source-pair-ready",
        Log.INFO,
        "event=source_pair state=ready backend=vulkan-spirv " +
          "source_pts0_us=${frame0.ptsUs} source_pts1_us=${frame1.ptsUs} source_delta_us=$deltaUs " +
          "target_fps=$targetFps playback_speed=${formatLogFloat(playbackSpeed)} " +
          "timestamp_source=media3_decoder_pts",
      )
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
    val outputState = if (synthesized && submitted) "synthesized" else "passthrough"
    val outputReason = lastBypassReason ?: if (outputState == "synthesized") "motion_pair_ready" else "source_frame_due"
    val outputSignature = "$outputState|$outputReason"
    if (outputSignature != lastLoggedOutputDiagnosticSignature) {
      lastLoggedOutputDiagnosticSignature = outputSignature
      val alpha = bracket?.let { Media3FlowCadence.interpolationAlpha(it.first.ptsUs, it.second.ptsUs, targetPtsUs) }
      val outputMessage =
        "MPVFLOW_DIAGNOSTIC component=native-media3 event=output state=$outputState backend=vulkan-spirv " +
          "reason=$outputReason target_fps=$targetFps speed=${formatLogFloat(speed)} " +
          "source_pts_us=${sourceFrame.ptsUs} source_pts0_us=${bracket?.first?.ptsUs ?: "none"} " +
          "source_pts1_us=${bracket?.second?.ptsUs ?: "none"} target_pts_us=$targetPtsUs " +
          "interpolation_alpha=${alpha?.let(::formatLogFloat) ?: "none"} " +
          "fallback_source_frame_pts_us=${if (outputState == "passthrough") sourceFrame.ptsUs else "none"} " +
          "timestamp_source=media3_decoder_pts sync_mode=1x"
      Log.println(if (outputState == "synthesized") Log.INFO else Log.WARN, logTag, outputMessage)
    }
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
    check(flowVulkanContext != 0L && pair.nativePairHandle != 0L && outputImageHandle != 0L) {
      "Media3 Flow Vulkan synthesis resources are unavailable"
    }
    check(Media3FlowVulkanNative.nativeSynthesize(
      flowVulkanContext, pair.nativePairHandle, alpha, outputImageHandle,
    )) { "Shared Vulkan frame synthesis failed" }
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

  private fun prepareSharedFrame(slot: FrameSlot) {
    check(flowVulkanContext != 0L && slot.nativeImageHandle != 0L) {
      "Media3 Flow frame is not backed by a Vulkan-shared HardwareBuffer"
    }
    // EGL writes the imported AHardwareBuffer; complete those writes before Vulkan takes ownership.
    GLES20.glFinish()
    check(Media3FlowVulkanNative.nativePrepareFrame(flowVulkanContext, slot.nativeImageHandle)) {
      "Vulkan could not stage the shared decoder frame"
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
        .forEach { releaseMotionPair(it) }
    }
    motionPairs.filter { it.available && retentionBoundaryUs >= it.frame1PtsUs }
      .forEach { releaseMotionPair(it) }
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
    if (flowVulkanContext == 0L && deviceSupportsVulkanFlow(appContext)) {
      flowVulkanContext = runCatching { Media3FlowVulkanNative.nativeCreateContext() }.getOrDefault(0L)
    }
    var allSharedImagesReady = flowVulkanContext != 0L
    var outputBuffer: HardwareBuffer? = null
    var outputHandle = 0L
    var outputGlTexture = 0
    if (flowVulkanContext != 0L) {
      outputBuffer = runCatching { createSharedHardwareBuffer(sourceWidth, sourceHeight, "output") }.getOrNull()
      if (outputBuffer != null) {
        outputHandle = runCatching {
          Media3FlowVulkanNative.nativeCreateFrameImage(
            flowVulkanContext, outputBuffer, sourceWidth, sourceHeight, true,
          )
        }.onFailure { error ->
          logFlowRecordOnce(
            "image-create-output-exception-$sourceWidth-$sourceHeight",
            Log.ERROR,
            "event=ahb_import state=failed stage=nativeCreateFrameImage image_role=output " +
              "width=$sourceWidth height=$sourceHeight reason=exception",
            error,
          )
        }.getOrDefault(0L)
        if (outputHandle == 0L) {
          logFlowRecordOnce(
            "image-create-output-rejected-$sourceWidth-$sourceHeight",
            Log.ERROR,
            "event=ahb_import state=failed stage=nativeCreateFrameImage image_role=output " +
              "width=$sourceWidth height=$sourceHeight reason=import_rejected",
          )
        }
        if (outputHandle != 0L) {
          outputGlTexture = runCatching {
            Media3FlowVulkanNative.nativeCreateEglTexture(outputBuffer)
          }.onFailure { error ->
            logFlowRecordOnce(
              "egl-image-create-output-exception-$sourceWidth-$sourceHeight",
              Log.ERROR,
              "event=egl_import state=failed stage=nativeCreateEglTexture image_role=output " +
                "width=$sourceWidth height=$sourceHeight reason=exception",
              error,
            )
          }.getOrDefault(0)
          if (outputGlTexture == 0) {
            logFlowRecordOnce(
              "egl-image-create-output-rejected-$sourceWidth-$sourceHeight",
              Log.ERROR,
              "event=egl_import state=failed stage=nativeCreateEglTexture image_role=output " +
                "width=$sourceWidth height=$sourceHeight reason=import_rejected",
            )
          }
        }
      }
    }
    if (outputHandle == 0L || outputGlTexture == 0) {
      allSharedImagesReady = false
      if (outputGlTexture != 0) deleteTexture(outputGlTexture)
      if (outputHandle != 0L && flowVulkanContext != 0L) {
        Media3FlowVulkanNative.nativeDestroyImage(flowVulkanContext, outputHandle)
      }
      outputBuffer?.close()
      outputBuffer = null
      outputHandle = 0L
      outputGlTexture = createTexture(GLES20.GL_TEXTURE_2D, sourceWidth, sourceHeight, GLES30.GL_RGBA8)
    }
    this.outputHardwareBuffer = outputBuffer
    outputImageHandle = outputHandle
    outputTexture = outputGlTexture
    repeat(MAX_STORED_FRAMES) {
      val buffer = if (flowVulkanContext != 0L) {
        runCatching { createSharedHardwareBuffer(sourceWidth, sourceHeight, "input") }.getOrNull()
      } else null
      var imageHandle = 0L
      var texture = 0
      if (buffer != null) {
        imageHandle = runCatching {
          Media3FlowVulkanNative.nativeCreateFrameImage(
            flowVulkanContext, buffer, sourceWidth, sourceHeight, false,
          )
        }.onFailure { error ->
          logFlowRecordOnce(
            "image-create-input-exception-$sourceWidth-$sourceHeight",
            Log.ERROR,
            "event=ahb_import state=failed stage=nativeCreateFrameImage image_role=input " +
              "width=$sourceWidth height=$sourceHeight reason=exception",
            error,
          )
        }.getOrDefault(0L)
        if (imageHandle == 0L) {
          logFlowRecordOnce(
            "image-create-input-rejected-$sourceWidth-$sourceHeight",
            Log.ERROR,
            "event=ahb_import state=failed stage=nativeCreateFrameImage image_role=input " +
              "width=$sourceWidth height=$sourceHeight reason=import_rejected",
          )
        }
        if (imageHandle != 0L) {
          texture = runCatching { Media3FlowVulkanNative.nativeCreateEglTexture(buffer) }
            .onFailure { error ->
              logFlowRecordOnce(
                "egl-image-create-input-exception-$sourceWidth-$sourceHeight",
                Log.ERROR,
                "event=egl_import state=failed stage=nativeCreateEglTexture image_role=input " +
                  "width=$sourceWidth height=$sourceHeight reason=exception",
                error,
              )
            }
            .getOrDefault(0)
          if (texture == 0) {
            logFlowRecordOnce(
              "egl-image-create-input-rejected-$sourceWidth-$sourceHeight",
              Log.ERROR,
              "event=egl_import state=failed stage=nativeCreateEglTexture image_role=input " +
                "width=$sourceWidth height=$sourceHeight reason=import_rejected",
            )
          }
        }
      }
      if (imageHandle == 0L || texture == 0) {
        allSharedImagesReady = false
        if (texture != 0) deleteTexture(texture)
        if (imageHandle != 0L && flowVulkanContext != 0L) {
          Media3FlowVulkanNative.nativeDestroyImage(flowVulkanContext, imageHandle)
        }
        buffer?.close()
        framePool += FrameSlot().apply {
          colorTexture = createTexture(GLES20.GL_TEXTURE_2D, sourceWidth, sourceHeight, GLES30.GL_RGBA8)
        }
      } else {
        framePool += FrameSlot().apply {
          hardwareBuffer = buffer
          nativeImageHandle = imageHandle
          colorTexture = texture
        }
      }
    }
    repeat(MAX_MOTION_PAIRS) { motionPairs += MotionPairSlot() }
    flowAvailable = allSharedImagesReady && framePool.size == MAX_STORED_FRAMES
    if (flowAvailable) {
      logFlowRecordOnce(
        "shared-image-pool-ready-$sourceWidth-$sourceHeight",
        Log.INFO,
        "event=renderer_init state=ready backend=vulkan-spirv target_fps=$targetFps " +
          "source=$sourceWidth x $sourceHeight input_images=${framePool.count { it.nativeImageHandle != 0L }} " +
          "required_input_images=$MAX_STORED_FRAMES output_image_present=${outputImageHandle != 0L} " +
          "hardware_decoder=media3 source_pts=media3_decoder_pts sync_mode=1x",
      )
    } else {
      lastBypassReason = "vulkan_shared_image_unavailable"
      logFlowRecordOnce(
        "shared-image-pool-failure-$sourceWidth-$sourceHeight",
        Log.ERROR,
        "event=renderer_init state=unavailable stage=shared_image_pool reason=vulkan_shared_image_unavailable " +
          "backend=vulkan-spirv source=$sourceWidth x $sourceHeight " +
          "input_images=${framePool.count { it.nativeImageHandle != 0L }} required_input_images=$MAX_STORED_FRAMES " +
          "output_image_present=${outputImageHandle != 0L} fallback=stock_media3_renderer",
      )
    }
    setOutputFrameRate()
    publishDiagnostics(if (flowAvailable) "gpu_ready" else "source_fallback", lastBypassReason, force = true)
  }

  private fun clearSourceFrames() {
    sourceFrames.forEach { it.inUse = false; it.ptsUs = C.TIME_UNSET }
    sourceFrames.clear()
    motionPairs.forEach {
      releaseMotionPair(it)
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

  private fun releaseMotionPair(slot: MotionPairSlot) {
    val handle = slot.nativePairHandle
    slot.nativePairHandle = 0L
    slot.available = false
    slot.frame0PtsUs = C.TIME_UNSET
    slot.frame1PtsUs = C.TIME_UNSET
    if (handle != 0L && flowVulkanContext != 0L) {
      runCatching { Media3FlowVulkanNative.nativeReleasePair(flowVulkanContext, handle) }
        .onFailure { Log.w(logTag, "Unable to release a Vulkan motion pair", it) }
    }
  }

  private fun destroyFrameResources() {
    motionPairs.forEach { releaseMotionPair(it) }
    motionPairs.clear()
    framePool.forEach { slot ->
      deleteTexture(slot.colorTexture)
      slot.colorTexture = 0
      if (slot.nativeImageHandle != 0L && flowVulkanContext != 0L) {
        Media3FlowVulkanNative.nativeDestroyImage(flowVulkanContext, slot.nativeImageHandle)
      }
      slot.nativeImageHandle = 0L
      slot.hardwareBuffer?.close()
      slot.hardwareBuffer = null
    }
    framePool.clear()
    deleteTexture(outputTexture)
    outputTexture = 0
    if (outputImageHandle != 0L && flowVulkanContext != 0L) {
      Media3FlowVulkanNative.nativeDestroyImage(flowVulkanContext, outputImageHandle)
    }
    outputImageHandle = 0L
    outputHardwareBuffer?.close()
    outputHardwareBuffer = null
  }

  private fun ensureEgl(): Boolean {
    if (eglReady) return true
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
        logFlowRecordOnce(
          "egl-get-display",
          Log.ERROR,
          "event=egl_context state=failed stage=eglGetDisplay egl_error=0x${EGL14.eglGetError().toString(16)}",
        )
        return false
      }
      val version = IntArray(2)
      if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
        logFlowRecordOnce(
          "egl-initialize",
          Log.ERROR,
          "event=egl_context state=failed stage=eglInitialize egl_error=0x${EGL14.eglGetError().toString(16)}",
        )
        return false
      }
      logFlowRecordOnce(
        "egl-initialize-ready",
        Log.INFO,
        "event=egl_context state=ready stage=eglInitialize egl_major=${version[0]} egl_minor=${version[1]}",
      )
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
      if (!EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0) || configCount[0] == 0) {
        logFlowRecordOnce(
          "egl-choose-config",
          Log.ERROR,
          "event=egl_context state=failed stage=eglChooseConfig config_count=${configCount[0]} " +
            "requested_es=3 requested_window_and_pbuffer=true egl_error=0x${EGL14.eglGetError().toString(16)}",
        )
        return false
      }
      eglConfig = configs[0]
    }
    if (eglContext == EGL14.EGL_NO_CONTEXT) {
      val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
      eglContext = EGL14.eglCreateContext(
        eglDisplay,
        checkNotNull(eglConfig),
        EGL14.EGL_NO_CONTEXT,
        contextAttributes,
        0,
      )
      if (eglContext == EGL14.EGL_NO_CONTEXT) {
        logFlowRecordOnce(
          "egl-create-context",
          Log.ERROR,
          "event=egl_context state=failed stage=eglCreateContext client_version=3 " +
            "egl_error=0x${EGL14.eglGetError().toString(16)}",
        )
        return false
      }
    }
    if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
      pbufferSurface = EGL14.eglCreatePbufferSurface(
        eglDisplay,
        checkNotNull(eglConfig),
        intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
        0,
      )
      if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
        logFlowRecordOnce(
          "egl-create-pbuffer",
          Log.ERROR,
          "event=egl_context state=failed stage=eglCreatePbufferSurface width=1 height=1 " +
            "egl_error=0x${EGL14.eglGetError().toString(16)}",
        )
        return false
      }
    }
    if (!makePbufferCurrent()) {
      logFlowRecordOnce(
        "egl-make-pbuffer-current",
        Log.ERROR,
        "event=egl_context state=failed stage=eglMakeCurrent target=pbuffer " +
          "egl_error=0x${EGL14.eglGetError().toString(16)}",
      )
      return false
    }
    val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
    if (!glVersion.contains("OpenGL ES 3.")) {
      lastBypassReason = "gles3_capture_unavailable"
      logFlowRecordOnce(
        "egl-gles-version",
        Log.ERROR,
        "event=egl_context state=failed stage=gles_version_validation gl_version=${glVersion.replace(' ', '_')} " +
          "required=OpenGL_ES_3 capture=gles3 compute=vulkan",
      )
      return false
    }
    if (!eglInfoLogged) {
      logFlowRecordOnce(
        "egl-gles-version-ready",
        Log.INFO,
        "event=egl_context state=ready stage=gles_version_validation gl_version=${glVersion.replace(' ', '_')} " +
          "capture=gles3 interpolation=Vulkan interpolation_compute=vulkan",
      )
      eglInfoLogged = true
    }
    if (!graphicsProgramsCompiled) {
      compileGraphicsPrograms()
      initializeGpuTimerQueries()
      graphicsProgramsCompiled = true
    }
    if (framebuffer == 0) {
      val ids = IntArray(1)
      GLES20.glGenFramebuffers(1, ids, 0)
      framebuffer = ids[0]
      val glError = GLES20.glGetError()
      if (framebuffer == 0 || glError != GLES20.GL_NO_ERROR) {
        logFlowRecordOnce(
          "egl-framebuffer-create",
          Log.ERROR,
          "event=egl_context state=failed stage=glGenFramebuffers framebuffer_id=$framebuffer " +
            "gl_error=0x${glError.toString(16)}",
        )
      }
    }
    eglReady = copyProgram != 0 && blitProgram != 0 && framebuffer != 0
    if (!eglReady) {
      logFlowRecordOnce(
        "egl-graphics-pipeline-validation",
        Log.ERROR,
        "event=egl_context state=failed stage=graphics_pipeline_validation " +
          "input_copy_program=$copyProgram presentation_blit_program=$blitProgram framebuffer=$framebuffer",
      )
    } else {
      logFlowRecordOnce(
        "egl-graphics-pipeline-ready",
        Log.INFO,
        "event=egl_context state=ready stage=graphics_pipeline_validation " +
          "input_copy_program=$copyProgram presentation_blit_program=$blitProgram framebuffer=$framebuffer",
      )
    }
    return eglReady
  }

  private fun compileGraphicsPrograms() {
    copyProgram = linkProgram("input_external_texture_copy", COPY_VERTEX_SHADER, COPY_EXTERNAL_FRAGMENT_SHADER)
    blitProgram = linkProgram("presentation_blit", COPY_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
  }

  private fun makePbufferCurrent(): Boolean {
    if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT ||
      pbufferSurface == EGL14.EGL_NO_SURFACE
    ) {
      logFlowRecordOnce(
        "egl-make-pbuffer-current",
        Log.ERROR,
        "event=egl_context state=failed stage=eglMakeCurrent target=pbuffer " +
          "display_ready=${eglDisplay != EGL14.EGL_NO_DISPLAY} " +
          "context_ready=${eglContext != EGL14.EGL_NO_CONTEXT} " +
          "surface_ready=${pbufferSurface != EGL14.EGL_NO_SURFACE}",
      )
      return false
    }
    val current = EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
    if (!current) {
      logFlowRecordOnce(
        "egl-make-pbuffer-current",
        Log.ERROR,
        "event=egl_context state=failed stage=eglMakeCurrent target=pbuffer " +
          "egl_error=0x${EGL14.eglGetError().toString(16)}",
      )
    }
    return current
  }

  private fun makeWindowCurrent(): Boolean {
    if (windowSurface == EGL14.EGL_NO_SURFACE || eglDisplay == EGL14.EGL_NO_DISPLAY ||
      eglContext == EGL14.EGL_NO_CONTEXT
    ) {
      logFlowRecordOnce(
        "egl-make-window-current",
        Log.ERROR,
        "event=egl_context state=failed stage=eglMakeCurrent target=window " +
          "display_ready=${eglDisplay != EGL14.EGL_NO_DISPLAY} " +
          "context_ready=${eglContext != EGL14.EGL_NO_CONTEXT} " +
          "surface_ready=${windowSurface != EGL14.EGL_NO_SURFACE}",
      )
      return false
    }
    val current = EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)
    if (!current) {
      logFlowRecordOnce(
        "egl-make-window-current",
        Log.ERROR,
        "event=egl_context state=failed stage=eglMakeCurrent target=window " +
          "egl_error=0x${EGL14.eglGetError().toString(16)}",
      )
    }
    return current
  }

  private fun destroyWindowSurface() {
    if (windowSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglDestroySurface(eglDisplay, windowSurface)
    }
    windowSurface = EGL14.EGL_NO_SURFACE
  }

  private fun createWindowSurface() {
    destroyWindowSurface()
    val surface = outputSurface ?: return
    if (!outputAvailable || !surface.isValid) {
      if (outputAvailable && !surface.isValid) {
        logFlowRecordOnce(
          "egl-window-surface-invalid",
          Log.WARN,
          "event=egl_context state=unavailable stage=window_surface_validation reason=surface_invalid",
        )
      }
      return
    }
    windowSurface = EGL14.eglCreateWindowSurface(
      eglDisplay,
      checkNotNull(eglConfig),
      surface,
      intArrayOf(EGL14.EGL_NONE),
      0,
    )
    if (windowSurface == EGL14.EGL_NO_SURFACE) {
      outputAvailable = false
      logFlowRecordOnce(
        "egl-window-surface-create",
        Log.ERROR,
        "event=egl_context state=failed stage=eglCreateWindowSurface " +
          "surface_valid=${surface.isValid} egl_error=0x${EGL14.eglGetError().toString(16)}",
      )
      reportError(IllegalStateException("Unable to create Media3 Flow EGL window surface"))
    } else {
      logFlowRecordOnce(
        "egl-window-surface-ready",
        Log.INFO,
        "event=egl_context state=ready stage=eglCreateWindowSurface output_surface_valid=true",
      )
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
      // The GL texture is used only for decoder capture and final presentation.
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

  private fun linkProgram(stage: String, vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader("$stage.vertex", GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader("$stage.fragment", GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (vertex == 0 || fragment == 0) {
      if (vertex != 0) GLES20.glDeleteShader(vertex)
      if (fragment != 0) GLES20.glDeleteShader(fragment)
      return 0
    }
    val program = GLES20.glCreateProgram()
    if (program == 0) {
      logFlowRecordOnce(
        "gles-create-program-$stage",
        Log.ERROR,
        "event=egl_context state=failed stage=glCreateProgram shader_program=$stage " +
          "gl_error=0x${GLES20.glGetError().toString(16)}",
      )
      GLES20.glDeleteShader(vertex)
      GLES20.glDeleteShader(fragment)
      return 0
    }
    GLES20.glAttachShader(program, vertex)
    GLES20.glAttachShader(program, fragment)
    GLES20.glLinkProgram(program)
    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    GLES20.glDeleteShader(vertex)
    GLES20.glDeleteShader(fragment)
    if (status[0] == 0) {
      val driverLog = GLES20.glGetProgramInfoLog(program).replace('\n', ' ').take(512).replace(' ', '_')
      logFlowRecordOnce(
        "gles-link-program-$stage",
        Log.ERROR,
        "event=egl_context state=failed stage=glLinkProgram shader_program=$stage " +
          "program_id=$program driver_log=$driverLog",
      )
      GLES20.glDeleteProgram(program)
      return 0
    }
    return program
  }

  private fun compileShader(stage: String, type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    if (shader == 0) {
      logFlowRecordOnce(
        "gles-create-shader-$stage",
        Log.ERROR,
        "event=egl_context state=failed stage=glCreateShader shader_stage=$stage " +
          "shader_type=0x${type.toString(16)} gl_error=0x${GLES20.glGetError().toString(16)}",
      )
      return 0
    }
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
      val driverLog = GLES20.glGetShaderInfoLog(shader).replace('\n', ' ').take(512).replace(' ', '_')
      logFlowRecordOnce(
        "gles-compile-shader-$stage",
        Log.ERROR,
        "event=egl_context state=failed stage=glCompileShader shader_stage=$stage " +
          "shader_id=$shader shader_type=0x${type.toString(16)} driver_log=$driverLog",
      )
      GLES20.glDeleteShader(shader)
      return 0
    }
    return shader
  }

  private fun destroyGlResources() {
    destroyFrameResources()
    if (flowVulkanContext != 0L) {
      Media3FlowVulkanNative.nativeDestroyContext(flowVulkanContext)
      flowVulkanContext = 0L
    }
    intArrayOf(copyProgram, blitProgram)
      .filter { it != 0 }
      .forEach { GLES20.glDeleteProgram(it) }
    copyProgram = 0
    blitProgram = 0
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
    val gpuElapsed = when {
      diagnostics.motionEstimateGpuMs == null -> "not_sampled_vulkan"
      else -> "${formatLogFloat(diagnostics.motionEstimateGpuMs * 1_000f)}us"
    }
    val confidence = diagnostics.confidence?.let { formatLogFloat(it) } ?: "n/a"
    val gpuStages = "presentation=${formatFlowDuration(gpuTiming.presentationDraw)}"
    val cpuStages = "queueWait=${formatFlowDuration(cpuTiming.inputQueueWait)} " +
      "frameHandler=${formatFlowDuration(cpuTiming.frameHandlerCall)} " +
      "eglSwapWait=${formatFlowDuration(cpuTiming.eglSwapWait)}"
    Log.i(
      logTag,
        "flow_summary backend=vulkan-spirv state=${diagnostics.state} bypass=${diagnostics.bypassReason ?: "none"} " +
        "positionUs=$latestPositionUs speed=${formatLogFloat(playbackSpeed)} " +
        "sourceFps=${formatLogFloat(diagnostics.sourceFps)} targetFps=${diagnostics.targetFps} " +
        "eglSwapFps=${formatLogFloat(diagnostics.outputFps)} generatedFps=${formatLogFloat(diagnostics.generatedFps)} " +
        "generatedTotal=${diagnostics.generatedFrames} dropsTotal=${diagnostics.droppedFrames} " +
        "missedOutputTicksTotal=${diagnostics.missedOutputTicks} skippedTotal=${diagnostics.skippedFrames} " +
        "input=$inputSize processing=${diagnostics.processingWidth}x${diagnostics.processingHeight} " +
        "motionPyramid=quarter>half>full " +
        "motionGrid=${diagnostics.motionGridWidth}x${diagnostics.motionGridHeight} " +
        "motionSubmitCpuMs=${formatLogFloat(diagnostics.motionEstimateSubmitMs)} " +
        "motionGpuElapsed=$gpuElapsed gpuTimerStatus=${gpuTiming.status} " +
        "gpuQueryCounts=valid:${gpuTiming.validResults},pending:${gpuTiming.pendingResults}," +
        "disjoint:${gpuTiming.disjointResultsDiscarded},zero:${gpuTiming.zeroDurationResults}," +
        "skipped:${gpuTiming.skippedBecausePoolFull} gpuStagesUs={$gpuStages} " +
        "cpuStagesUs={$cpuStages} coverage=unavailable_vulkan_ssbo_readback_disabled " +
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
    if (errors.isNotEmpty()) {
      val codes = errors.joinToString(",") { "0x${it.toString(16)}" }
      logFlowRecordOnce(
        "gles-operation-${stage.replace(' ', '_')}",
        Log.ERROR,
        "event=egl_runtime state=failed stage=gles_operation operation=${stage.replace(' ', '_')} " +
          "gl_errors=$codes error_count=${errors.size}",
      )
      throw IllegalStateException("$stage GLES error(s) $codes")
    }
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
    private const val NO_FRAME_TIME_NANOS = Long.MIN_VALUE
    private const val GL_TIME_ELAPSED_EXT = 0x88BF
    private const val GL_GPU_DISJOINT_EXT = 0x8FBB
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

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
    fun deviceSupportsVulkanFlow(context: Context): Boolean =
      BuildConfig.MPV_SUPPORTS_VULKAN && Build.VERSION.SDK_INT >= 26 &&
        context.packageManager.hasSystemFeature("android.hardware.vulkan.version")

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
