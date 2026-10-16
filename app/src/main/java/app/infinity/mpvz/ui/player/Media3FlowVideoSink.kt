package app.infinity.mpvz.ui.player

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.media.MediaFormat
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
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Size
import androidx.media3.common.util.TimestampIterator
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.exoplayer.video.VideoSink
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.preferences.effectiveMpvFlowTargetFps
import app.infinity.mpvz.preferences.MpvFlowComputeApi
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

internal class Media3FlowSdrOutputException(message: String) : IllegalStateException(message)

/** Diagnostics emitted by the Media3-owned GPU interpolation route. */
data class Media3FlowDiagnostics(
  val enabled: Boolean = false,
  /** Median cadence of accepted SurfaceTexture capture PTS; not nominal container FPS. */
  val capturedSourceFps: Float = 0f,
  val capturedPtsSampleCount: Int = 0,
  /** Successful eglSwapBuffers return cadence; this is not a SurfaceFlinger scanout measurement. */
  val outputFps: Float = 0f,
  val generatedFps: Float = 0f,
  val targetFps: Int = 0,
  val generatedFrames: Long = 0L,
  /** Actual sink frame drops; inferred output-tick gaps are reported separately. */
  val droppedFrames: Long = 0L,
  /** Inferred target ticks with no sink output; not confirmed display/scanout misses. */
  val skippedOutputTicks: Long = 0L,
  /** Staging, GLES/EGL, or window/swap submission failures, distinct from inferred tick gaps. */
  val outputSubmissionFailures: Long = 0L,
  val lastOutputSubmissionFailureReason: String = "none",
  val outputSubmissionFailureReasons: Map<String, Long> = emptyMap(),
  val presentationPathFailureReasons: Map<String, Long> = emptyMap(),
  val lastNoWritablePresentationSlotStates: Map<String, Int> = emptyMap(),
  val skippedFrames: Long = 0L,
  /** CPU time spent submitting motion-estimation commands, not a GPU-completion timer. */
  val motionEstimateSubmitMs: Float = 0f,
  /** Average Vulkan timestamp-query duration for one motion-search dispatch, not a per-pair estimate. */
  val motionPassAverageGpuUs: Double? = null,
  /** Null until a non-blocking GPU confidence readback is available. */
  val confidence: Float? = null,
  val motionGridWidth: Int = 0,
  val motionGridHeight: Int = 0,
  /** Motion-search dimensions, measured from the capped interpolation frame. */
  val processingWidth: Int = 0,
  val processingHeight: Int = 0,
  /** Actual GPU-stored and synthesized frame dimensions after the 1920px cap. */
  val frameStorageWidth: Int = 0,
  val frameStorageHeight: Int = 0,
  val state: String = "off",
  val bypassReason: String? = null,
  val vulkanTimings: FlowVulkanTimingStats = FlowVulkanTimingStats(),
  val glesTimings: FlowGlesTimingStats = FlowGlesTimingStats(),
  val cpuTimings: FlowCpuTimingStats = FlowCpuTimingStats(),
  val pixelCoverage: FlowPixelCoverageStats = FlowPixelCoverageStats(),
  val pendingInputDepth: Int = 0,
  val inputInFlight: Boolean = false,
  val oldestPendingInputAgeMs: Float = 0f,
  val sourceFrameDepth: Int = 0,
  val readyMotionPairDepth: Int = 0,
  val presentationQueueDepth: Int = 0,
  val presentationInFlight: Int = 0,
  val presentationCoalescedFrames: Long = 0L,
  val selectedSourcePtsAgeMs: Float? = null,
  val sourcePairPtsGapMs: Float? = null,
  val frame0CaptureAgeMs: Float? = null,
  val frame1CaptureAgeMs: Float? = null,
  val outputCallbackLatenessMs: Float = 0f,
  val lastOutputTickGapReason: String = "none",
  val outputTickGapContexts: Map<String, Long> = emptyMap(),
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
  requestedQuality: Int,
  private val requestedComputeApi: MpvFlowComputeApi,
  private val visibilityResolverEnabled: Boolean,
  private val softBlendEnabled: Boolean,
  private val onDiagnostics: (Media3FlowDiagnostics) -> Unit,
) : VideoSink {
  private val appContext = context.applicationContext
  private val isDebuggable =
    (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  private val logTag = "Mpv∞-Media3Flow"
  private val displayHz = displayRefreshRate(appContext)
  private val targetFps = effectiveMpvFlowTargetFps(requestedTargetFps, displayHz)
  private val quality = requestedQuality.coerceIn(0, 100)
  private val maxDimension = Media3FlowAnalysisPolicy.maxDimension(targetFps, quality)
  private val glThread = HandlerThread("Media3Flow-GLES").apply { start() }
  private val glHandler = Handler(glThread.looper)
  private val presentationThread = HandlerThread("Media3Flow-Present").apply { start() }
  private val presentationHandler = Handler(presentationThread.looper)
  private val presentationQueue = Media3FlowPresentationQueue(MAX_PRESENTATION_BUFFERS)
  private val presentationSlots = Array(MAX_PRESENTATION_BUFFERS) { PresentationSlot() }
  private val presentationDrainPending = AtomicBoolean(false)
  private var presenterCurrentSlotIndex = -1
  private val outputSurfaceGeneration = AtomicLong(0L)
  private val inputLock = Any()
  private val pendingInput = ArrayDeque<PendingInput>()
  private val expectedTexturePts = ArrayDeque<FrameToken>()
  private val sourceFrames = ArrayDeque<FrameSlot>()
  private val framePool = ArrayList<FrameSlot>(MAX_STORED_FRAMES)
  private val motionPairs = ArrayList<MotionPairSlot>(MAX_MOTION_PAIRS)
  private val deferredMotionPairs = Media3FlowDeferredPairQueue(MAX_MOTION_PAIRS)
  private val ptsHistory = ArrayDeque<Long>()
  private val outputWallTimes = ArrayDeque<Long>()
  private val pendingGpuTimerQueries = ArrayDeque<PendingGlesTimerQuery>()
  private val glesDurationSamplesNs = Array(FlowGlesStage.values().size) { ArrayDeque<Long>() }
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
  private val presentationVertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(6 * 2 * Float.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
    .apply { position(0) }

  @Volatile private var currentFormat: Format? = null
  @Volatile private var decoderOutputColorStandard = Format.NO_VALUE
  @Volatile private var decoderOutputColorTransfer = Format.NO_VALUE
  @Volatile private var decoderSdrOutputConfirmed = false
  @Volatile private var decoderSdrOutputFailureReported = false
  @Volatile private var videoAspect = VideoAspect.Fit
  @Volatile private var initialized = false
  @Volatile private var outputAvailable = false
  @Volatile private var presentationSurfaceReady = false
  @Volatile private var presentationFailed = false
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
  private val skippedOutputTicks = AtomicLong(0L)
  private val outputSubmissionFailures = AtomicLong(0L)
  private val presentationFailureCounters = FlowPresentationFailureCounters()
  private var skippedFrames = 0L
  private var lastMotionSubmitMs = 0f
  private var cachedVulkanTimingStats = FlowVulkanTimingStats()
  private var lastVulkanTimingPollAtNs = 0L
  private var lastSelectedSourcePtsAgeMs: Float? = null
  private var lastSourcePairPtsGapMs: Float? = null
  private var lastFrame0CaptureAgeMs: Float? = null
  private var lastFrame1CaptureAgeMs: Float? = null
  private var lastOutputCallbackLatenessMs = 0f
  private var lastOutputTickGapReason = "none"
  private val outputTickGapContexts = linkedMapOf<String, Long>()
  private var lastPresentationStageFailureReason = "producer_submission_failed_unclassified"
  private var lastPresentationStageSlotStates = emptyMap<String, Int>()
  private var gpuTimerQueryIds = IntArray(0)
  private var gpuTimerQueriesSupported = false
  private var activeGpuTimerQuery: PendingGlesTimerQuery? = null
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
  private val outputDecisionCounters = FlowOutputDecisionCounters()
  private val motionPairAnalysisCounters = FlowMotionPairAnalysisCounters()
  private var lastOutputDiagnosticLogAtNs = 0L
  @Volatile private var diagnosticCaptureSession: String? = null

  private var eglDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext = EGL14.EGL_NO_CONTEXT
  private var eglReady = false
  private var eglConfig: android.opengl.EGLConfig? = null
  private var pbufferSurface = EGL14.EGL_NO_SURFACE
  private var windowSurface = EGL14.EGL_NO_SURFACE
  private var presentationEglContext = EGL14.EGL_NO_CONTEXT
  private var presentationPbufferSurface = EGL14.EGL_NO_SURFACE
  private var presentationBlitProgram = 0
  private var presentationBlitImageLocation = -1
  private var presentationBlitTextureScaleLocation = -1
  private var presentationBlitTextureOffsetLocation = -1
  private var presentationBlitRotationLocation = -1
  @Volatile private var eglSwapIntervalAttempted = false
  @Volatile private var eglSwapIntervalZeroApplied = false
  private var presentationReleaseStarted = false
  @Volatile private var outputSurface: Surface? = null
  private var inputTextureId = 0
  private var inputSurfaceTexture: SurfaceTexture? = null
  private var inputSurface: Surface? = null
  private var copyProgram = 0
  private var blitProgram = 0
  private var copyExternalLocation = -1
  private var copyTextureMatrixLocation = -1
  private var copyDownscaleFactorLocation = -1
  private var copyOutputTexelSizeLocation = -1
  private var blitImageLocation = -1
  private var blitTextureScaleLocation = -1
  private var blitTextureOffsetLocation = -1
  private var blitRotationLocation = -1
  private var copyBt2020ToBt709Location = -1
  private var framebuffer = 0
  private var graphicsProgramsCompiled = false
  private var frameWidth = 0
  private var frameHeight = 0
  private var decoderInputWidth = 0
  private var decoderInputHeight = 0
  private var processingWidth = 0
  private var processingHeight = 0
  private var gridWidth = 0
  private var gridHeight = 0
  private var outputTexture = 0
  @Volatile private var flowVulkanContext = 0L
  private var inputRequiredHardwareBufferUsage = 0L
  private var outputRequiredHardwareBufferUsage = 0L
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
      "event=config requested_enabled=true renderer=native-media3 capture=gles3 renderer_api=vulkan " +
        "requested_compute_api=${requestedComputeApi.name.lowercase()} " +
        "visibility_resolver=$visibilityResolverEnabled soft_blend=$softBlendEnabled " +
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
            flowVulkanContext =
              Media3FlowVulkanNative.nativeCreateContext(maxDimension, requestedComputeApi.nativeValue)
            if (flowVulkanContext == 0L) {
              "vulkan_context_or_compute_setup_failed"
            } else {
              inputRequiredHardwareBufferUsage =
                Media3FlowVulkanNative.nativeGetRequiredHardwareBufferUsage(flowVulkanContext, output = false)
              outputRequiredHardwareBufferUsage =
                Media3FlowVulkanNative.nativeGetRequiredHardwareBufferUsage(flowVulkanContext, output = true)
              when {
                inputRequiredHardwareBufferUsage == 0L -> "vulkan_hardwarebuffer_input_usage_query_failed"
                outputRequiredHardwareBufferUsage == 0L -> "vulkan_hardwarebuffer_output_usage_query_failed"
                !preflightSharedImage(output = false) -> "vulkan_hardwarebuffer_input_preflight_failed"
                !preflightSharedImage(output = true) -> "vulkan_hardwarebuffer_output_preflight_failed"
                else -> null
              }
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
        "event=preflight state=ready renderer_api=vulkan " +
          "requested_compute_api=${requestedComputeApi.name.lowercase()} " +
          "effective_compute_api=${effectiveComputeApiName()} target_fps=$targetFps " +
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

  private fun effectiveComputeApiName(): String {
    val context = flowVulkanContext
    if (context == 0L) return "unavailable"
    return runCatching {
      Media3FlowVulkanNative.nativeGetComputeApiName(context)
    }.getOrNull() ?: "unknown"
  }

  fun isPreflightAvailable(): Boolean = preflightAvailable && !disposed

  fun startFlowDiagnosticCapture(sessionId: String, baseDirectory: String): String? = runCatching {
    require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid capture session ID" }
    runOnGlThreadSync {
      val context = flowVulkanContext
      check(!disposed && preflightAvailable && flowAvailable && context != 0L) { "Flow/Vulkan resources unavailable" }
      check(diagnosticCaptureSession == null) { "Diagnostic capture already active" }
      val format = currentFormat ?: error("Video color metadata unavailable")
      val color = format.colorInfo ?: error("Video color metadata unknown")
      check(color.colorSpace != Format.NO_VALUE && color.colorTransfer != Format.NO_VALUE && color.colorRange != Format.NO_VALUE) { "Video color metadata unknown" }
      check(!Media3FlowFormatPolicy.isDolbyVision(format.sampleMimeType.orEmpty(), format.codecs.orEmpty())) { "Dolby Vision capture unsupported" }
      check(color.hdrStaticInfo == null && color.colorTransfer != C.COLOR_TRANSFER_ST2084 && color.colorTransfer != C.COLOR_TRANSFER_HLG) { "HDR/Dolby Vision capture unsupported" }
      check(decoderSdrOutputConfirmed) { "Resolved decoder output color is not confirmed SDR" }
      val backend = effectiveComputeApiName().replace(Regex("[^A-Za-z0-9_-]"), "")
      val json =
        buildString {
          append("{\"backend\":\"$backend\",\"target_fps\":$targetFps,\"quality\":$quality,")
          append("\"maximum_dimension\":$maxDimension,\"color_space\":${color.colorSpace},")
          append("\"color_transfer\":${color.colorTransfer},\"color_range\":${color.colorRange},")
          append("\"source_width\":$frameWidth,\"source_height\":$frameHeight,")
          append("\"processing_width\":$processingWidth,\"processing_height\":$processingHeight,")
          append("\"surface_width\":${outputResolution.width},\"surface_height\":${outputResolution.height},")
          append("\"visibility_resolver\":$visibilityResolverEnabled,\"soft_blend\":$softBlendEnabled}")
        }
      val failure = Media3FlowVulkanNative.nativeStartDiagnosticCapture(context, sessionId, baseDirectory, json)
      check(failure == null) { failure ?: "Native capture start failed" }
      diagnosticCaptureSession = sessionId
      null
    }
  }.getOrElse { it.message ?: "Diagnostic capture could not start" }

  fun stopFlowDiagnosticCapture(sessionId: String): String? = runCatching {
    runOnGlThreadSync {
      val activeSession = diagnosticCaptureSession ?: return@runOnGlThreadSync null
      check(activeSession == sessionId) { "Capture session ID mismatch" }
      val result = Media3FlowVulkanNative.nativeStopDiagnosticCapture(flowVulkanContext, sessionId)
      if (result == null) diagnosticCaptureSession = null
      result
    }
  }.getOrElse { it.message ?: "Diagnostic capture could not stop" }

  private fun createSharedHardwareBuffer(width: Int, height: Int, role: String): HardwareBuffer {
    val baseUsage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
    val driverRequiredUsage =
      when {
        role.startsWith("output") -> outputRequiredHardwareBufferUsage
        else -> inputRequiredHardwareBufferUsage
      }
    if (flowVulkanContext != 0L && driverRequiredUsage == 0L) {
      throw IllegalStateException("Vulkan did not provide required AHardwareBuffer usage for $role")
    }
    val usage = baseUsage or driverRequiredUsage
    logFlowRecordOnce(
      "ahb-usage-$role",
      Log.INFO,
      "event=ahb_usage state=allocation image_role=$role base_usage=0x${baseUsage.toString(16)} " +
        "required_ahb_usage=0x${driverRequiredUsage.toString(16)} buffer_usage=0x${usage.toString(16)}",
    )
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
  private data class InputQueueSnapshot(val depth: Int, val inFlight: Boolean, val oldestAgeMs: Float)
  private data class FrameToken(val ptsUs: Long, val generation: Long, val releaseTimestampNs: Long)
  private data class PlaybackClock(val positionUs: Long, val elapsedRealtimeUs: Long)
  private enum class FlowGlesStage { PRESENTATION_STAGING_COPY }
  private enum class FlowCpuStage {
    INPUT_QUEUE_WAIT,
    FRAME_HANDLER_CALL,
    EGL_SWAP_WAIT,
    VULKAN_INPUT_PREPARE,
    VULKAN_SYNTHESIS_SUBMIT,
  }
  private data class PendingGlesTimerQuery(
    val id: Int,
    val generation: Long,
  )

  private class FrameSlot {
    var hardwareBuffer: HardwareBuffer? = null
    var nativeImageHandle = 0L
    var colorTexture = 0
    var ptsUs = C.TIME_UNSET
    var capturedAtNs = 0L
    var inUse = false
  }

  private class PresentationSlot {
    var textureId = 0
    var width = 0
    var height = 0
    var producerFence = 0L
    var consumerFence = 0L
    var consumerFenceStartedAtNs = 0L
    var consumerFencePollScheduled = false
    var frame: PresentationFrame? = null
    var completionReported = false
  }

  private data class PresentationFrame(
    val sourcePtsUs: Long,
    val sourcePts0Us: Long?,
    val sourcePts1Us: Long?,
    val targetPtsUs: Long,
    val renderTimeNs: Long,
    val interpolationAlpha: Float?,
    val speed: Float,
    val synthesized: Boolean,
    val reason: String,
    val streamGeneration: Long,
    val surfaceGeneration: Long,
    val format: Format?,
    val frameWidth: Int,
    val frameHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val aspect: VideoAspect,
  )

  private class MotionPairSlot {
    var nativePairHandle = 0L
    var frame0PtsUs = C.TIME_UNSET
    var frame1PtsUs = C.TIME_UNSET
    var available = false
    var motionEstimateSubmitMs = 0f
  }

  override fun setListener(listener: VideoSink.Listener, executor: Executor) {
    this.listener = listener
    listenerExecutor = executor
  }

  override fun initialize(sourceFormat: Format): Boolean {
    currentFormat = sourceFormat
    resetDecoderOutputColorState(sourceFormat)
    initialized = false
    val success = runOnGlThreadSync {
      if (!ensureEgl() || !makePbufferCurrent() ||
        !runOnPresentationThreadSync { ensurePresentationEgl() } ||
        sourceFormat.width <= 0 || sourceFormat.height <= 0
      ) {
        destroyWindowSurface()
        initialized = false
        return@runOnGlThreadSync false
      }
      configureInputSurface(sourceFormat.width, sourceFormat.height)
      ensureProcessingResources(sourceFormat.width, sourceFormat.height)
      val sinkInitialized = inputSurface != null && inputSurfaceTexture != null
      initialized = sinkInitialized
      if (Media3FlowOutputSurfacePolicy.shouldCreateEglWindowSurface(
          sinkInitialized = sinkInitialized,
          outputSurfaceValid = outputSurface?.isValid == true,
          disposed = disposed,
        )) {
        createWindowSurface()
      }
      sinkInitialized
    }
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
      if (makePbufferCurrent()) cancelPendingPresentationBuffers()
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
    outputSurfaceGeneration.incrementAndGet()
    presentationSurfaceReady = false
    this.outputSurface = outputSurface
    this.outputResolution = outputResolution
    outputAvailable = outputSurface.isValid
    glHandler.post {
      firstFrameReported = false
      if (previousSurface !== outputSurface) {
        clearFrameRateHint(previousSurface)
        outputWallTimes.clear()
        generatedWallTimes.clear()
        if (makePbufferCurrent()) cancelPendingPresentationBuffers()
      }
      // The dormant Flow renderer shares PlayerView's surface; only claim its BufferQueue after
      // Media3 selects this sink, otherwise the stock MediaCodec renderer cannot connect.
      val canCreateOutputWindowSurface = this.outputSurface === outputSurface &&
        Media3FlowOutputSurfacePolicy.shouldCreateEglWindowSurface(
          sinkInitialized = initialized,
          outputSurfaceValid = outputSurface.isValid,
          disposed = disposed,
        )
      if (!canCreateOutputWindowSurface) return@post
      if (!ensureEgl() || !makePbufferCurrent()) return@post
      createWindowSurface()
      scheduleDisplayFrame()
    }
  }

  override fun clearOutputSurfaceInfo() {
    val previousSurface = outputSurface
    outputSurfaceGeneration.incrementAndGet()
    outputAvailable = false
    presentationSurfaceReady = false
    outputSurface = null
    glHandler.post {
      cancelDisplayFrame()
      clearFrameRateHint(previousSurface)
      outputWallTimes.clear()
      generatedWallTimes.clear()
      if (makePbufferCurrent()) cancelPendingPresentationBuffers()
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
    resetDecoderOutputColorState(format)
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

  /** Receives the codec-negotiated output color fields after Media3 configures the decoder. */
  fun onDecoderOutputFormatChanged(format: Format, outputFormat: MediaFormat?) {
    val colorInfo = format.colorInfo
    val requestSdrOutput = requiresSdrOutputTransferRequest(format)
    val outputStandard = outputFormat?.let { mediaFormat ->
      runCatching {
        if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
          mediaFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD)
        } else {
          Format.NO_VALUE
        }
      }.getOrDefault(Format.NO_VALUE)
    }?.takeUnless { it == Format.NO_VALUE } ?: colorInfo?.colorSpace ?: Format.NO_VALUE
    val outputTransfer = outputFormat?.let { mediaFormat ->
      runCatching {
        if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
          mediaFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        } else {
          Format.NO_VALUE
        }
      }.getOrDefault(Format.NO_VALUE)
    }?.takeUnless { it == Format.NO_VALUE } ?:
      if (requestSdrOutput) Format.NO_VALUE else colorInfo?.colorTransfer ?: Format.NO_VALUE

    decoderOutputColorStandard = outputStandard
    decoderOutputColorTransfer = outputTransfer
    decoderSdrOutputConfirmed = !requestSdrOutput || outputTransfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    decoderSdrOutputFailureReported = false
    logFlowRecordOnce(
      "decoder-color-${format.sampleMimeType}-${format.width}x${format.height}",
      if (decoderSdrOutputConfirmed) Log.INFO else Log.WARN,
      "event=decoder_color state=${if (decoderSdrOutputConfirmed) "ready" else "unverified"} " +
        "request_sdr_video=$requestSdrOutput output_standard=$outputStandard " +
        "output_transfer=$outputTransfer source_transfer=${colorInfo?.colorTransfer ?: Format.NO_VALUE} " +
        "source_resolution=${format.width}x${format.height} frame_storage_cap=$MEDIA3_FLOW_FRAME_MAX_DIMENSION",
    )
  }

  private fun resetDecoderOutputColorState(format: Format) {
    val requestSdrOutput = requiresSdrOutputTransferRequest(format)
    decoderOutputColorStandard = format.colorInfo?.colorSpace ?: Format.NO_VALUE
    decoderOutputColorTransfer = if (requestSdrOutput) {
      Format.NO_VALUE
    } else {
      format.colorInfo?.colorTransfer ?: Format.NO_VALUE
    }
    decoderSdrOutputConfirmed = !requestSdrOutput
    decoderSdrOutputFailureReported = false
  }

  private fun isDecoderOutputSafeForStorage(texture: SurfaceTexture): Boolean {
    val requestSdrOutput = currentFormat?.let(::requiresSdrOutputTransferRequest) == true
    if (decoderOutputColorTransfer == C.COLOR_TRANSFER_ST2084 ||
      decoderOutputColorTransfer == C.COLOR_TRANSFER_HLG ||
      decoderOutputColorTransfer == C.COLOR_TRANSFER_LINEAR
    ) return false
    if (Build.VERSION.SDK_INT >= 33) {
      val dataSpace = Api33.dataSpace(texture)
      val transfer = Api33.transfer(dataSpace)
      Api33.colorSpace(dataSpace)?.let { decoderOutputColorStandard = it }
      if (transfer == Api33.TRANSFER_HLG || transfer == Api33.TRANSFER_ST2084 ||
        transfer == Api33.TRANSFER_LINEAR
      ) return false
      if (!requestSdrOutput) return true
      if (decoderOutputColorTransfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) {
        decoderSdrOutputConfirmed = true
        return true
      }
      if (transfer == Api33.TRANSFER_SMPTE_170M || transfer == Api33.TRANSFER_SRGB ||
        transfer == Api33.TRANSFER_GAMMA2_2 || transfer == Api33.TRANSFER_GAMMA2_6 ||
        transfer == Api33.TRANSFER_GAMMA2_8
      ) {
        decoderSdrOutputConfirmed = true
        return true
      }
      return false
    }
    return !requestSdrOutput || decoderOutputColorTransfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
  }

  override fun allowReleaseFirstFrameBeforeStarted() {
    allowFirstFrameBeforeStarted = true
  }

  override fun handleInputFrame(
    bufferPresentationTimeUs: Long,
    videoFrameHandler: VideoSink.VideoFrameHandler,
  ): Boolean {
    if (!initialized || disposed) return false
    val ptsUs = Media3FlowCadence.checkedAdd(bufferPresentationTimeUs, inputTimestampAdjustmentUs)
    if (ptsUs == null) {
      runCatching { videoFrameHandler.skip() }
      droppedFrames.incrementAndGet()
      dispatchListener { it.onFrameDropped() }
      logFlowRecordOnce(
        "input-pts-adjustment-overflow",
        Log.WARN,
        "event=input_frame state=dropped stage=timestamp_adjustment reason=signed_long_overflow",
      )
      return true
    }
    synchronized(inputLock) {
      val buffered = pendingInput.size + storedFrameCount + if (inputInFlight) 1 else 0
      if (buffered >= MAX_QUEUED_FRAMES) return false
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
    val adjustedPositionUs = Media3FlowCadence.checkedAdd(positionUs, inputTimestampAdjustmentUs)
    val elapsedMediaUs = (elapsedSinceLoopUs.toDouble() * speed.toDouble()).roundToLong()
    val currentPositionUs = adjustedPositionUs?.let {
      Media3FlowCadence.checkedAdd(it, elapsedMediaUs)
    }
    if (currentPositionUs == null) {
      logFlowRecordOnce(
        "playback-position-overflow",
        Log.WARN,
        "event=clock state=skipped stage=position_adjustment reason=signed_long_overflow",
      )
      return
    }
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

  /** Latest-vsync-wins: a blocked swap coalesces display requests instead of replaying stale vsyncs. */
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
        val latestRequestVersion = renderRequestVersion.get()
        val latestRequestIsOneShot = renderFrameTimeNanos.get() == NO_FRAME_TIME_NANOS
        renderTaskPending.set(false)
        if (Media3FlowCadence.shouldImmediatelyResubmitCoalescedRender(
            requestVersion,
            latestRequestVersion,
            latestRequestIsOneShot,
          ) &&
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
    pollGlesTimerQueries()
    val speed = playbackSpeed
    val clock = playbackClock
    val nowElapsedUs = SystemClock.elapsedRealtimeNanos() / 1_000L
    val elapsedSinceClockUs =
      if (running && nowElapsedUs >= clock.elapsedRealtimeUs) nowElapsedUs - clock.elapsedRealtimeUs else 0L
    val elapsedMediaUs = (elapsedSinceClockUs.toDouble() * speed.toDouble()).roundToLong()
    val positionUs = Media3FlowCadence.checkedAdd(clock.positionUs, elapsedMediaUs)
    if (positionUs == null) {
      logFlowRecordOnce(
        "display-position-overflow",
        Log.WARN,
        "event=clock state=skipped stage=display_position_projection reason=signed_long_overflow",
      )
      return
    }
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
    outputAvailable = false
    presentationSurfaceReady = false
    outputSurfaceGeneration.incrementAndGet()
    outputSurface = null
    val discarded = ArrayList<PendingInput>()
    synchronized(inputLock) {
      while (pendingInput.isNotEmpty()) discarded += pendingInput.removeFirst()
    }
    discarded.forEach { runCatching { it.handler.skip() } }
    glHandler.post {
      cancelDisplayFrame()
      clearFrameRateHint(previousSurface)
      if (makePbufferCurrent()) cancelPendingPresentationBuffers()
      inputSurface?.release()
      inputSurface = null
      inputSurfaceTexture?.release()
      inputSurfaceTexture = null
      presentationHandler.post {
        cancelPendingPresentationBuffersOnPresentationThread()
        destroyWindowSurfaceOnPresentationThread()
        finishPresentationThreadRelease()
      }
    }
  }

  private fun finishPresentationThreadRelease() {
    if (presentationQueue.hasPresenting()) {
      presentationHandler.postDelayed({ finishPresentationThreadRelease() }, PRESENTATION_FENCE_POLL_DELAY_MS)
      return
    }
    if (presentationReleaseStarted) return
    presentationReleaseStarted = true
    makePresentationPbufferCurrent()
    if (presentationBlitProgram != 0) GLES20.glDeleteProgram(presentationBlitProgram)
    presentationBlitProgram = 0
    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglMakeCurrent(
        eglDisplay,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_CONTEXT,
      )
      if (presentationPbufferSurface != EGL14.EGL_NO_SURFACE) {
        EGL14.eglDestroySurface(eglDisplay, presentationPbufferSurface)
      }
      if (presentationEglContext != EGL14.EGL_NO_CONTEXT) {
        EGL14.eglDestroyContext(eglDisplay, presentationEglContext)
      }
    }
    presentationPbufferSurface = EGL14.EGL_NO_SURFACE
    presentationEglContext = EGL14.EGL_NO_CONTEXT
    presentationThread.quitSafely()
    glHandler.post { finishGlThreadRelease() }
  }

  private fun finishGlThreadRelease() {
    makePbufferCurrent()
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
      // This renders only into the sink's private capture SurfaceTexture. The visible window is
      // scheduled separately from playback PTS in renderAtPosition, so a bounded frame lookahead
      // supplies interpolation inputs without advancing on-screen presentation or audio timing.
      // Handler.render is called on Media3's playback thread; GPU work stays on the dedicated GL
      // thread.
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
      val format = currentFormat
      if (!isDecoderOutputSafeForStorage(texture)) {
        droppedFrames.incrementAndGet()
        dispatchListener { it.onFrameDropped() }
        if (!decoderSdrOutputFailureReported) {
          decoderSdrOutputFailureReported = true
          val error = Media3FlowSdrOutputException(
            "Media3 decoder did not produce verified SDR output; refusing to copy HDR/unknown-transfer " +
              "frames into RGBA8 interpolation storage (transfer=$decoderOutputColorTransfer)",
          )
          val formatLogName = format?.sampleMimeType ?: "unknown"
          val formatLogResolution = format?.let { "${it.width}x${it.height}" } ?: "unknown"
          val sourceColorTransfer = format?.colorInfo?.colorTransfer ?: Format.NO_VALUE
          logFlowRecordOnce(
            "decoder-sdr-transfer-rejected-$formatLogName-$formatLogResolution",
            Log.ERROR,
            "event=decoder_color state=failed reason=sdr_transfer_request_unhonored " +
              "output_transfer=$decoderOutputColorTransfer source_transfer=$sourceColorTransfer " +
              "source_resolution=$formatLogResolution fallback=media3_error_recovery",
            error,
          )
          reportError(error)
        }
        return
      }
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
    val captureStartedNs = System.nanoTime()
    val previousPts = sourceFrames.lastOrNull()?.ptsUs
    if (previousPts != null && ptsUs <= previousPts) {
      skippedFrames++
      droppedFrames.incrementAndGet()
      dispatchListener { it.onFrameDropped() }
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
    if (!makeSharedImageExternal(slot, "input_buffer_reuse")) {
      droppedFrames.incrementAndGet()
      lastBypassReason = "input_buffer_ownership_handoff_failed"
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
    slot.capturedAtNs = captureStartedNs
    slot.inUse = true
    sourceFrames.addLast(slot)
    if (originPtsUs == C.TIME_UNSET) {
      val streamStartUs = streamStartPositionUs.takeIf { it != C.TIME_UNSET }
      val adjustedStreamStartUs = streamStartUs?.let {
        Media3FlowCadence.checkedAdd(it, inputTimestampAdjustmentUs)
      }
      if (streamStartUs != null && adjustedStreamStartUs == null) {
        logFlowRecordOnce(
          "stream-origin-overflow",
          Log.WARN,
          "event=clock state=fallback stage=stream_origin reason=signed_long_overflow",
        )
      }
      originPtsUs = adjustedStreamStartUs ?: ptsUs
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
      Log.i(logTag, "GLES presentation timer unavailable; Vulkan compute timestamp queries are reported separately")
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

  private fun beginGlesTimerQuery(generation: Long = streamGeneration): PendingGlesTimerQuery? {
    if (!gpuTimerQueriesSupported) return null
    if (activeGpuTimerQuery != null) {
      skippedGpuTimerQueries++
      return null
    }
    pollGlesTimerQueries()
    val inUse = pendingGpuTimerQueries.mapTo(HashSet()) { it.id }
    val queryId = gpuTimerQueryIds.firstOrNull { it !in inUse }
    if (queryId == null) {
      skippedGpuTimerQueries++
      return null
    }
    val query = PendingGlesTimerQuery(queryId, generation)
    GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queryId)
    activeGpuTimerQuery = query
    return query
  }

  private fun endGlesTimerQuery(query: PendingGlesTimerQuery?) {
    if (query == null || activeGpuTimerQuery?.id != query.id) return
    GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
    pendingGpuTimerQueries.addLast(query)
    activeGpuTimerQuery = null
  }

  /** Only available query results are read; a zero-timeout availability check never waits. */
  private fun pollGlesTimerQueries() {
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
      val stageSamples = glesDurationSamplesNs[FlowGlesStage.PRESENTATION_STAGING_COPY.ordinal]
      stageSamples.addLast(elapsedNs)
      while (stageSamples.size > GPU_TIMING_HISTORY_SIZE) stageSamples.removeFirst()
    }
  }

  private fun recordOutputTickGaps(count: Long, reason: String) {
    if (count <= 0L) return
    skippedOutputTicks.addAndGet(count)
    lastOutputTickGapReason = reason
    outputTickGapContexts[reason] = (outputTickGapContexts[reason] ?: 0L) + count
  }

  private fun recordOutputSubmissionFailure(
    reason: String,
    detail: String = reason,
    slotStates: Map<String, Int> = emptyMap(),
  ) {
    outputSubmissionFailures.incrementAndGet()
    presentationFailureCounters.recordOutputSubmissionFailure(reason, detail, slotStates)
  }

  private fun recordPresentationPathFailure(reason: String) {
    presentationFailureCounters.recordPresentationPathFailure(reason)
  }

  private fun recordCpuDuration(stage: FlowCpuStage, elapsedNs: Long) {
    synchronized(cpuTimingLock) {
      val samples = cpuDurationSamplesNs[stage.ordinal]
      samples.addLast(elapsedNs.coerceAtLeast(0L))
      while (samples.size > CPU_TIMING_HISTORY_SIZE) samples.removeFirst()
    }
  }

  private fun glesTimingSnapshot(): FlowGlesTimingStats {
    val status = when {
      !gpuTimerQueriesSupported -> "unsupported"
      validGpuTimerResults > 0L && disjointGpuTimerResults > 0L -> "valid_with_disjoint_results"
      validGpuTimerResults > 0L -> "valid"
      disjointGpuTimerResults > 0L -> "disjoint_only"
      pendingGpuTimerQueries.isNotEmpty() -> "pending"
      skippedGpuTimerQueries > 0L -> "query_pool_limited"
      else -> "waiting_for_samples"
    }
    fun summary(stage: FlowGlesStage) =
      Media3FlowDiagnosticMath.summarizeNanoseconds(glesDurationSamplesNs[stage.ordinal].toList())
    return FlowGlesTimingStats(
      supported = gpuTimerQueriesSupported,
      status = status,
      validResults = validGpuTimerResults,
      pendingResults = pendingGpuTimerQueries.size + if (activeGpuTimerQuery != null) 1 else 0,
      disjointResultsDiscarded = disjointGpuTimerResults,
      zeroDurationResults = zeroGpuTimerResults,
      skippedBecausePoolFull = skippedGpuTimerQueries,
      presentationStagingCopy = summary(FlowGlesStage.PRESENTATION_STAGING_COPY),
    )
  }

  /** Polling libplacebo's timestamp query ring is nonblocking; never call pl_gpu_finish here. */
  private fun vulkanTimingSnapshot(nowNs: Long): FlowVulkanTimingStats {
    if (flowVulkanContext == 0L) return FlowVulkanTimingStats(status = "unsupported_context")
    if (nowNs - lastVulkanTimingPollAtNs < 250_000_000L) return cachedVulkanTimingStats
    lastVulkanTimingPollAtNs = nowNs
    cachedVulkanTimingStats = Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(
      runCatching {
        Media3FlowVulkanNative.nativeGetVulkanTimingStats(flowVulkanContext)
      }.getOrNull(),
    )
    return cachedVulkanTimingStats
  }

  private fun inputQueueSnapshot(nowNs: Long): InputQueueSnapshot = synchronized(inputLock) {
    val oldestAgeMs = pendingInput.firstOrNull()?.let {
      ((nowNs - it.enqueuedAtNs).coerceAtLeast(0L) / 1_000_000.0).toFloat()
    } ?: 0f
    InputQueueSnapshot(pendingInput.size, inputInFlight, oldestAgeMs)
  }

  private fun cpuTimingSnapshot(): FlowCpuTimingStats = synchronized(cpuTimingLock) {
    fun summary(stage: FlowCpuStage) =
      Media3FlowDiagnosticMath.summarizeNanoseconds(cpuDurationSamplesNs[stage.ordinal].toList())
    FlowCpuTimingStats(
      inputQueueWait = summary(FlowCpuStage.INPUT_QUEUE_WAIT),
      frameHandlerCall = summary(FlowCpuStage.FRAME_HANDLER_CALL),
      eglSwapWait = summary(FlowCpuStage.EGL_SWAP_WAIT),
      vulkanInputPrepare = summary(FlowCpuStage.VULKAN_INPUT_PREPARE),
      vulkanSynthesisSubmit = summary(FlowCpuStage.VULKAN_SYNTHESIS_SUBMIT),
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
    val deltaUs = Media3FlowCadence.checkedSubtract(frame1.ptsUs, frame0.ptsUs) ?: 0L
    when {
      deltaUs <= 0L -> lastBypassReason = "invalid_source_delta"
      !Media3FlowCadence.needsInterpolation(deltaUs, targetFps, playbackSpeed) ->
        lastBypassReason = "source_meets_target"
      motionPairs.none {
        it.available && it.frame0PtsUs == frame0.ptsUs && it.frame1PtsUs == frame1.ptsUs
      } -> deferredMotionPairs.enqueue(frame0.ptsUs, frame1.ptsUs)
    }
    analyzeDeferredMotionPair()
  }

  /**
   * Retries one retained pair when a reusable slot is available; no output waits for analysis.
   * Pair PTS keys are revalidated against the current adjacent source-frame list before JNI use.
   */
  private fun analyzeDeferredMotionPair() {
    if (!flowAvailable || sourceFrames.size < 2 || deferredMotionPairs.size == 0) return
    val reusable = motionPairs.firstOrNull {
      !it.available || retentionBoundaryUs(latestPositionUs) >= it.frame1PtsUs
    }
    val sourceSnapshot = sourceFrames.toList()
    val sourcePts = LongArray(sourceSnapshot.size) { sourceSnapshot[it].ptsUs }
    val preferredPair = preferredMotionPairForNextOutput(sourceSnapshot)
    val candidate = deferredMotionPairs.takeNextRetainedAdjacent(
      retainedFramePtsUs = sourcePts,
      retentionBoundaryUs = retentionBoundaryUs(latestPositionUs),
      analysisAllowed = reusable != null,
      preferredPair = preferredPair,
    )
    if (candidate == null) {
      when {
        deferredMotionPairs.size == 0 -> motionPairAnalysisCounters.recordNoRetainedCandidate()
        reusable == null -> motionPairAnalysisCounters.recordDeferredNoReusableSlot()
      }
      lastBypassReason = when {
        reusable == null -> "motion_pair_queue_full"
        else -> lastBypassReason
      }
      return
    }
    val frame0 = sourceFrames.firstOrNull { it.ptsUs == candidate.frame0PtsUs }
    val frame1 = sourceFrames.firstOrNull { it.ptsUs == candidate.frame1PtsUs }
    if (frame0 == null || frame1 == null) return
    val deltaUs = Media3FlowCadence.checkedSubtract(frame1.ptsUs, frame0.ptsUs) ?: return
    if (motionPairs.any {
        it.available && it.frame0PtsUs == candidate.frame0PtsUs &&
          it.frame1PtsUs == candidate.frame1PtsUs
      }
    ) return
    val slot = checkNotNull(reusable)
    releaseMotionPair(slot)
    slot.frame0PtsUs = candidate.frame0PtsUs
    slot.frame1PtsUs = candidate.frame1PtsUs
    val startNs = System.nanoTime()
    motionPairAnalysisCounters.recordAttempt()
    val nativePair = runCatching {
      Media3FlowVulkanNative.nativeAnalyzePair(
        flowVulkanContext, frame0.nativeImageHandle, frame1.nativeImageHandle,
      )
    }.getOrDefault(0L)
    lastMotionSubmitMs = (System.nanoTime() - startNs) / 1_000_000f
    slot.motionEstimateSubmitMs = lastMotionSubmitMs
    if (nativePair == 0L) {
      motionPairAnalysisCounters.recordFailure()
      slot.available = false
      flowAvailable = false
      lastBypassReason = "motion_analysis_error"
      lastState = "source_fallback"
      logFlowRecordOnce(
        "first-pair-analysis-failure",
        Log.ERROR,
        "event=source_pair state=failed renderer_api=vulkan " +
          "requested_compute_api=${requestedComputeApi.name.lowercase()} " +
          "effective_compute_api=${effectiveComputeApiName()} stage=nativeAnalyzePair " +
          "source_pts0_us=${frame0.ptsUs} source_pts1_us=${frame1.ptsUs} source_delta_us=$deltaUs " +
          "target_fps=$targetFps playback_speed=${formatLogFloat(playbackSpeed)} " +
          "reason=motion_analysis_error fallback=original_media3_source_frames",
      )
      return
    }
    slot.nativePairHandle = nativePair
    slot.available = true
    motionPairAnalysisCounters.recordReady()
    lastBypassReason = null
    lastState = "motion_estimated_gpu"
    if (!firstSuccessfulPairDiagnosticLogged) {
      firstSuccessfulPairDiagnosticLogged = true
      logFlowRecordOnce(
        "first-source-pair-ready",
        Log.INFO,
        "event=source_pair state=ready renderer_api=vulkan " +
          "requested_compute_api=${requestedComputeApi.name.lowercase()} " +
          "effective_compute_api=${effectiveComputeApiName()} " +
          "source_pts0_us=${frame0.ptsUs} source_pts1_us=${frame1.ptsUs} source_delta_us=$deltaUs " +
          "target_fps=$targetFps playback_speed=${formatLogFloat(playbackSpeed)} " +
          "timestamp_source=media3_decoder_pts",
      )
    }
  }

  private fun renderAtPosition(positionUs: Long, speed: Float, frameTimeNanos: Long?) {
    if ((!running && !allowFirstFrameBeforeStarted) || !outputAvailable || !presentationSurfaceReady) return
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
    val missedTicks = if (tick != null && lastOutputTick >= 0L) {
      Media3FlowCadence.skippedTicks(lastOutputTick, tick)
    } else {
      0L
    }
    val sourcePts = LongArray(sourceSnapshot.size) { sourceSnapshot[it].ptsUs }
    val sourceIndex = Media3FlowCadence.sourceFrameIndexAtOrBefore(sourcePts, targetPtsUs)
    val sourceFrame = sourceIndex?.let(sourceSnapshot::get)
    if (sourceFrame == null) {
      skippedFrames++
      lastBypassReason = "waiting_for_source_pts"
      recordOutputTickGaps(missedTicks.coerceAtLeast(if (tick != null) 1L else 0L), "source_pts_not_available")
      logTimingTrace(positionUs, speed, targetPtsUs, null, sourceSnapshot.first().ptsUs, null, System.nanoTime())
      return
    }
    val bracket = findSourcePair(sourceSnapshot, targetPtsUs)
    val pair = bracket?.let { (a, b) ->
      motionPairs.firstOrNull { it.available && it.frame0PtsUs == a.ptsUs && it.frame1PtsUs == b.ptsUs }
    }
    val deltaUs = bracket?.let {
      Media3FlowCadence.checkedSubtract(it.second.ptsUs, it.first.ptsUs)
    } ?: 0L
    val motionAdmissionProxyMs = pair?.let {
      Media3FlowCadence.motionAdmissionProxyMs(it.motionEstimateSubmitMs, null)
    } ?: Float.POSITIVE_INFINITY
    val synthesize = bracket != null && pair != null &&
      Media3FlowCadence.needsInterpolation(deltaUs, targetFps, speed) &&
      motionAdmissionProxyMs < deltaUs / 1000f * ANALYSIS_DEADLINE_RESERVE
    val nowNs = System.nanoTime()
    lastOutputCallbackLatenessMs = frameTimeNanos?.let {
      ((nowNs - it).coerceAtLeast(0L) / 1_000_000.0).toFloat()
    } ?: 0f
    lastSelectedSourcePtsAgeMs = Media3FlowCadence.checkedSubtract(positionUs, sourceFrame.ptsUs)
      ?.coerceAtLeast(0L)
      ?.div(1_000f)
    lastSourcePairPtsGapMs = bracket?.let { (a, b) ->
      Media3FlowCadence.checkedSubtract(b.ptsUs, a.ptsUs)?.coerceAtLeast(0L)?.div(1_000f)
    }
    lastFrame0CaptureAgeMs = bracket?.first?.capturedAtNs?.takeIf { it > 0L }?.let {
      ((nowNs - it).coerceAtLeast(0L) / 1_000_000.0).toFloat()
    }
    lastFrame1CaptureAgeMs = bracket?.second?.capturedAtNs?.takeIf { it > 0L }?.let {
      ((nowNs - it).coerceAtLeast(0L) / 1_000_000.0).toFloat()
    }
    if (missedTicks > 0L) {
      val wantsInterpolation = bracket != null &&
        Media3FlowCadence.needsInterpolation(deltaUs, targetFps, speed)
      val missReason = when {
        bracket == null -> "no_source_pair_for_target_pts"
        pair == null && wantsInterpolation -> "motion_pair_not_ready"
        pair != null && wantsInterpolation && motionAdmissionProxyMs >= deltaUs / 1_000f * ANALYSIS_DEADLINE_RESERVE ->
          "motion_submit_proxy_over_deadline_reserve"
        lastOutputCallbackLatenessMs >= 1_000f / targetFps.coerceAtLeast(1) -> "gl_callback_late_by_one_or_more_ticks"
        else -> "output_tick_gap_unattributed"
      }
      recordOutputTickGaps(missedTicks, missReason)
      lastBypassReason = "output_tick_gap_inferred"
    }
    val basePresentationNs = frameTimeNanos?.let {
      val refreshHz = displayHz.takeIf { rate -> rate.isFinite() && rate > 0f }?.toDouble() ?: targetFps.toDouble()
      Media3FlowCadence.checkedAdd(it, (1_000_000_000.0 / refreshHz).roundToLong())
    } ?: nowNs
    val ptsOffsetNs = Media3FlowCadence.checkedSubtract(targetPtsUs, positionUs)?.let { offsetUs ->
      val offsetNs = (offsetUs.toDouble() / speed.toDouble()) * 1_000.0
      offsetNs.takeIf {
        it.isFinite() && it >= Long.MIN_VALUE.toDouble() && it <= Long.MAX_VALUE.toDouble()
      }?.toLong()
    }
    val renderTimeNs = ptsOffsetNs?.let { Media3FlowCadence.checkedAdd(basePresentationNs, it) }
      ?.coerceAtLeast(nowNs) ?: nowNs
    logTimingTrace(positionUs, speed, targetPtsUs, sourceFrame.ptsUs, sourceSnapshot.first().ptsUs, renderTimeNs, nowNs)
    val alpha = bracket?.let { Media3FlowCadence.interpolationAlpha(it.first.ptsUs, it.second.ptsUs, targetPtsUs) }
    fun presentationFrame(synthesized: Boolean, reason: String) = PresentationFrame(
      sourcePtsUs = sourceFrame.ptsUs,
      sourcePts0Us = bracket?.first?.ptsUs,
      sourcePts1Us = bracket?.second?.ptsUs,
      targetPtsUs = targetPtsUs,
      renderTimeNs = renderTimeNs,
      interpolationAlpha = alpha,
      speed = speed,
      synthesized = synthesized,
      reason = reason,
      streamGeneration = streamGeneration,
      surfaceGeneration = outputSurfaceGeneration.get(),
      format = currentFormat,
      frameWidth = frameWidth,
      frameHeight = frameHeight,
      outputWidth = outputResolution.width.takeIf { it > 0 } ?: frameWidth,
      outputHeight = outputResolution.height.takeIf { it > 0 } ?: frameHeight,
      aspect = videoAspect,
    )
    lastPresentationStageFailureReason = "producer_submission_failed_unclassified"
    lastPresentationStageSlotStates = emptyMap()
    var synthesized = false
    val submitted = if (synthesize && bracket != null && pair != null) {
      val synthesisResult = runCatching {
        dispatchSynthesis(bracket.first, bracket.second, pair, targetPtsUs)
        checkGlError("frame synthesis")
        drawTexture(outputTexture, renderTimeNs, targetPtsUs, presentationFrame(true, "motion_pair_ready"))
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
        drawSourceFrame(sourceFrame, renderTimeNs, targetPtsUs, presentationFrame(false, lastBypassReason ?: "source_frame_due"))
      }
    } else {
      if (bracket != null && Media3FlowCadence.needsInterpolation(deltaUs, targetFps, speed)) {
        skippedFrames++
        if (lastBypassReason == null) lastBypassReason = "motion_not_ready_or_deadline"
      }
      drawSourceFrame(sourceFrame, renderTimeNs, targetPtsUs, presentationFrame(false, lastBypassReason ?: "source_frame_due"))
    }
    if (!submitted) {
      recordOutputSubmissionFailure(
        lastPresentationStageFailureReason,
        slotStates = lastPresentationStageSlotStates,
      )
      droppedFrames.incrementAndGet()
      dispatchListener { it.onFrameDropped() }
      outputDecisionCounters.record("output_submission_failed", "presentation_stage_failed")
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

  /** Prefer a pair for the next renderable cadence tick; missed intervals are not replayed. */
  private fun preferredMotionPairForNextOutput(frames: List<FrameSlot>): Media3FlowSourcePair? {
    if (frames.size < 2) return null
    val origin = originPtsUs.takeIf { it != C.TIME_UNSET } ?: frames.first().ptsUs
    val dueTick = Media3FlowCadence.outputTick(latestPositionUs, origin, targetFps, playbackSpeed)
      ?: return null
    val firstTick = Media3FlowCadence.nextRenderableOutputTick(dueTick, lastOutputTick) ?: return null
    for (offset in 0L..1L) {
      val tick = Media3FlowCadence.checkedAdd(firstTick, offset) ?: continue
      val targetPtsUs = Media3FlowCadence.outputTimestampUs(tick, origin, targetFps, playbackSpeed)
        ?: continue
      val (frame0, frame1) = findSourcePair(frames, targetPtsUs) ?: continue
      val deltaUs = Media3FlowCadence.checkedSubtract(frame1.ptsUs, frame0.ptsUs) ?: continue
      if (Media3FlowCadence.needsInterpolation(deltaUs, targetFps, playbackSpeed)) {
        return Media3FlowSourcePair(frame0.ptsUs, frame1.ptsUs)
      }
    }
    return null
  }

  private fun dispatchSynthesis(a: FrameSlot, b: FrameSlot, pair: MotionPairSlot, targetPtsUs: Long) {
    val alpha = Media3FlowCadence.interpolationAlpha(a.ptsUs, b.ptsUs, targetPtsUs) ?: return
    check(flowVulkanContext != 0L && pair.nativePairHandle != 0L && outputImageHandle != 0L) {
      "Media3 Flow Vulkan synthesis resources are unavailable"
    }
    val submitStartNs = System.nanoTime()
    try {
      check(
        Media3FlowVulkanNative.nativeSynthesize(
          flowVulkanContext,
          pair.nativePairHandle,
          a.nativeImageHandle,
          b.nativeImageHandle,
          a.ptsUs,
          b.ptsUs,
          targetPtsUs,
          alpha,
          visibilityResolverEnabled,
          softBlendEnabled,
          outputImageHandle,
        ),
      ) {
        "Shared Vulkan frame synthesis failed"
      }
    } finally {
      recordCpuDuration(FlowCpuStage.VULKAN_SYNTHESIS_SUBMIT, (System.nanoTime() - submitStartNs).coerceAtLeast(0L))
    }
  }

  private fun drawSourceFrame(
    slot: FrameSlot,
    presentationTimeNs: Long,
    ptsUs: Long,
    frame: PresentationFrame? = null,
  ): Boolean {
    if (!makeSharedImageExternal(slot, "source_frame_display")) {
      lastPresentationStageFailureReason = "producer_shared_image_external_handoff_failed"
      return false
    }
    return drawTexture(slot.colorTexture, presentationTimeNs, ptsUs, frame)
  }

  private fun drawTexture(
    textureId: Int,
    presentationTimeNs: Long,
    ptsUs: Long,
    frame: PresentationFrame? = null,
  ): Boolean {
    when {
      presentationFailed -> {
        lastPresentationStageFailureReason = "producer_presentation_path_unavailable"
        return false
      }
      !presentationSurfaceReady -> {
        lastPresentationStageFailureReason = "producer_presentation_surface_unavailable"
        return false
      }
      !outputAvailable -> {
        lastPresentationStageFailureReason = "producer_output_unavailable"
        return false
      }
      frameWidth <= 0 || frameHeight <= 0 -> {
        lastPresentationStageFailureReason = "producer_invalid_frame_dimensions"
        return false
      }
    }
    if (!ensureEgl()) {
      lastPresentationStageFailureReason = "producer_egl_context_unavailable"
      return false
    }
    if (!makePbufferCurrent()) {
      lastPresentationStageFailureReason = "producer_egl_pbuffer_current_failed"
      return false
    }
    val acquisition = presentationQueue.acquireForWriteWithSnapshot()
    val lease = acquisition.lease
    if (lease == null) {
      lastPresentationStageFailureReason = "producer_no_writable_presentation_slot"
      lastPresentationStageSlotStates = acquisition.slotStates.entries.associate { (state, count) ->
        state.name.lowercase() to count
      }
      return false
    }
    val slot = presentationSlots[lease.slot]
    clearProducerFence(slot)
    var producerFence = 0L
    var failureStage = "staging_texture_allocation"
    try {
      if (slot.textureId == 0 || slot.width != frameWidth || slot.height != frameHeight) {
        deleteTexture(slot.textureId)
        slot.textureId = createTexture(GLES20.GL_TEXTURE_2D, frameWidth, frameHeight, GLES30.GL_RGBA8)
        slot.width = frameWidth
        slot.height = frameHeight
      }
      val glesQuery = beginGlesTimerQuery()
      try {
        failureStage = "staging_framebuffer_setup"
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
          GLES20.GL_FRAMEBUFFER,
          GLES20.GL_COLOR_ATTACHMENT0,
          GLES20.GL_TEXTURE_2D,
          slot.textureId,
          0,
        )
        failureStage = "staging_framebuffer_validation"
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
          "Media3 Flow presentation-staging framebuffer is incomplete"
        }
        GLES20.glViewport(0, 0, frameWidth, frameHeight)
        GLES20.glUseProgram(blitProgram)
        bindTextureUnit(0, textureId)
        GLES20.glUniform1i(blitImageLocation, 0)
        GLES20.glUniform2f(blitTextureScaleLocation, 1f, 1f)
        GLES20.glUniform2f(blitTextureOffsetLocation, 0f, 0f)
        GLES20.glUniform1i(blitRotationLocation, 0)
        failureStage = "staging_blit_draw"
        drawFullscreenTriangle()
        failureStage = "staging_gl_error_check"
        checkGlError("presentation staging copy")
      } finally {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        endGlesTimerQuery(glesQuery)
      }
      failureStage = "producer_fence_creation"
      producerFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
      check(producerFence != 0L) { "Unable to fence the staged Media3 Flow frame" }
      failureStage = "producer_command_flush"
      GLES20.glFlush()
      slot.producerFence = producerFence
      slot.frame = frame ?: defaultPresentationFrame(ptsUs, presentationTimeNs)
      slot.completionReported = false
      failureStage = "presentation_queue_publish"
      val superseded = presentationQueue.publish(lease)
      if (superseded != null) {
        val replaced = presentationSlots[superseded]
        clearProducerFence(replaced)
        replaced.frame = null
      }
      schedulePresentationDrain()
      return true
    } catch (error: RuntimeException) {
      if (producerFence != 0L) GLES30.glDeleteSync(producerFence)
      slot.producerFence = 0L
      slot.frame = null
      presentationQueue.abortWrite(lease)
      lastPresentationStageFailureReason = "producer_${failureStage}_failed"
      logFlowRecordOnce(
        "presentation-stage-failed",
        Log.ERROR,
        "event=presentation_handoff state=failed stage=$failureStage " +
          "reason=$lastPresentationStageFailureReason exception=${error.javaClass.simpleName}",
        error,
      )
      return false
    }
  }

  private fun defaultPresentationFrame(ptsUs: Long, presentationTimeNs: Long): PresentationFrame {
    val format = currentFormat
    return PresentationFrame(
      sourcePtsUs = ptsUs,
      sourcePts0Us = null,
      sourcePts1Us = null,
      targetPtsUs = ptsUs,
      renderTimeNs = presentationTimeNs,
      interpolationAlpha = null,
      speed = playbackSpeed,
      synthesized = false,
      reason = "source_frame_due",
      streamGeneration = streamGeneration,
      surfaceGeneration = outputSurfaceGeneration.get(),
      format = format,
      frameWidth = frameWidth,
      frameHeight = frameHeight,
      outputWidth = outputResolution.width.takeIf { it > 0 } ?: frameWidth,
      outputHeight = outputResolution.height.takeIf { it > 0 } ?: frameHeight,
      aspect = videoAspect,
    )
  }

  private fun clearProducerFence(slot: PresentationSlot) {
    if (slot.producerFence != 0L) GLES30.glDeleteSync(slot.producerFence)
    slot.producerFence = 0L
  }

  /** Runs on the producer GL handler; only READY slots can be cancelled here. */
  private fun cancelPendingPresentationBuffers() {
    presentationQueue.cancelReady().forEach { index ->
      val slot = presentationSlots[index]
      clearProducerFence(slot)
      slot.frame = null
    }
  }

  private fun schedulePresentationDrain() {
    if (disposed || presentationFailed || !presentationDrainPending.compareAndSet(false, true)) return
    if (!presentationHandler.post {
        presenterCurrentSlotIndex = -1
        try {
          presentNextQueuedFrame()
        } catch (error: Throwable) {
          handlePresenterException(error)
        } finally {
          presenterCurrentSlotIndex = -1
        }
      }
    ) {
      presentationDrainPending.set(false)
    }
  }

  /** Runs exclusively on the presentation handler; eglSwapBuffers never blocks capture or synthesis. */
  private fun presentNextQueuedFrame() {
    val index = presentationQueue.takeNext()
    if (index == null) {
      presentationDrainPending.set(false)
      if (presentationQueue.hasReady()) schedulePresentationDrain()
      return
    }
    val slot = presentationSlots[index]
    presenterCurrentSlotIndex = index
    val frame = slot.frame
    if (frame == null || disposed || presentationFailed || !outputAvailable || !presentationSurfaceReady ||
      frame.streamGeneration != streamGeneration || frame.surfaceGeneration != outputSurfaceGeneration.get()
    ) {
      makePresentationPbufferCurrent()
      discardPresentationSlot(index)
      resumePresentationDrain()
      return
    }
    if (slot.producerFence == 0L) {
      failUnprovenPresentation(
        index,
        frame,
        "producer_fence_missing",
        GLES30.GL_WAIT_FAILED,
        "presenter_producer_fence_missing",
      )
      return
    }
    if (!makePresentationWindowCurrent()) {
      val error = EGL14.eglGetError()
      recordPresentationPathFailure("presenter_egl_make_current_failed")
      makePresentationPbufferCurrent()
      discardPresentationSlot(index)
      slot.completionReported = true
      reportPresentationCompletion(
        frame,
        swapped = false,
        swapWaitNs = 0L,
        swapReturnedNs = System.nanoTime(),
        errorCode = error,
        errorKind = "presenter_egl_make_current",
      )
      resumePresentationDrain()
      return
    }

    val producerFence = slot.producerFence
    slot.producerFence = 0L
    GLES30.glWaitSync(producerFence, 0, GLES30.GL_TIMEOUT_IGNORED)
    val producerWaitError = consumePresentationGlError()
    if (producerWaitError != GLES20.GL_NO_ERROR) {
      runCatching { GLES30.glDeleteSync(producerFence) }
      failUnprovenPresentation(
        index,
        frame,
        "producer_fence_wait",
        producerWaitError,
        "presenter_producer_fence_wait",
      )
      return
    }
    GLES30.glDeleteSync(producerFence)
    if (disposed || !outputAvailable || !presentationSurfaceReady ||
      frame.streamGeneration != streamGeneration || frame.surfaceGeneration != outputSurfaceGeneration.get()
    ) {
      discardPresentationSlot(index)
      resumePresentationDrain()
      return
    }
    val geometry = Media3FlowGeometry.blitGeometry(
      frame.frameWidth,
      frame.frameHeight,
      frame.outputWidth,
      frame.outputHeight,
      frame.aspect,
      pixelWidthHeightRatio = frame.format?.pixelWidthHeightRatio?.takeIf { it.isFinite() && it > 0f } ?: 1f,
      rotationDegrees = frame.format?.rotationDegrees ?: 0,
    )
    val hasLetterbox = geometry.viewportWidth != frame.outputWidth || geometry.viewportHeight != frame.outputHeight
    if (hasLetterbox) {
      GLES20.glViewport(0, 0, frame.outputWidth, frame.outputHeight)
      GLES20.glClearColor(0f, 0f, 0f, 1f)
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }
    GLES20.glViewport(geometry.viewportX, geometry.viewportY, geometry.viewportWidth, geometry.viewportHeight)
    GLES20.glUseProgram(presentationBlitProgram)
    bindTextureUnit(0, slot.textureId)
    GLES20.glUniform1i(presentationBlitImageLocation, 0)
    GLES20.glUniform2f(presentationBlitTextureScaleLocation, geometry.textureScaleX, geometry.textureScaleY)
    GLES20.glUniform2f(presentationBlitTextureOffsetLocation, geometry.textureOffsetX, geometry.textureOffsetY)
    GLES20.glUniform1i(presentationBlitRotationLocation, geometry.rotationDegrees)
    drawPresentationFullscreenTriangle()
    val drawError = consumePresentationGlError()
    val presentationTimeSet = EGLExt.eglPresentationTimeANDROID(
      eglDisplay,
      windowSurface,
      frame.renderTimeNs.coerceAtLeast(0L),
    )
    val presentationTimeError = if (presentationTimeSet) EGL14.EGL_SUCCESS else EGL14.eglGetError()
    slot.consumerFence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
    if (slot.consumerFence == 0L) {
      val fenceError = consumePresentationGlError().takeIf { it != GLES20.GL_NO_ERROR } ?: GLES30.GL_WAIT_FAILED
      failUnprovenPresentation(
        index,
        frame,
        "consumer_fence_create",
        fenceError,
        "presenter_consumer_fence_create",
      )
      return
    }
    if (!schedulePresentationFencePoll(index, slot, flushCommands = true)) {
      resumePresentationDrain()
      return
    }
    if (disposed || !outputAvailable || !presentationSurfaceReady ||
      frame.streamGeneration != streamGeneration || frame.surfaceGeneration != outputSurfaceGeneration.get()
    ) {
      resumePresentationDrain()
      return
    }
    if (drawError != GLES20.GL_NO_ERROR) {
      recordPresentationPathFailure("presenter_gles_draw_failed")
      slot.completionReported = true
      reportPresentationCompletion(
        frame,
        swapped = false,
        swapWaitNs = 0L,
        swapReturnedNs = System.nanoTime(),
        errorCode = drawError,
        errorKind = "presenter_gles_draw",
      )
      failPresentationPath("presenter_draw")
      resumePresentationDrain()
      return
    }
    if (!presentationTimeSet) {
      recordPresentationPathFailure("presenter_egl_presentation_time_failed")
      slot.completionReported = true
      reportPresentationCompletion(
        frame,
        swapped = false,
        swapWaitNs = 0L,
        swapReturnedNs = System.nanoTime(),
        errorCode = presentationTimeError,
        errorKind = "presenter_egl_presentation_time",
      )
      resumePresentationDrain()
      return
    }
    val swapStartNs = System.nanoTime()
    val swapped = EGL14.eglSwapBuffers(eglDisplay, windowSurface)
    val swapReturnedNs = System.nanoTime()
    val swapWaitNs = (swapReturnedNs - swapStartNs).coerceAtLeast(0L)
    val swapError = if (swapped) EGL14.EGL_SUCCESS else EGL14.eglGetError()
    if (!swapped) recordPresentationPathFailure("presenter_egl_swap_failed")
    slot.completionReported = true
    reportPresentationCompletion(
      frame,
      swapped,
      swapWaitNs,
      swapReturnedNs,
      swapError,
      errorKind = "presenter_egl_swap",
      swapAttempted = true,
    )
    resumePresentationDrain()
  }

  private fun failUnprovenPresentation(
    index: Int,
    frame: PresentationFrame,
    stage: String,
    errorCode: Int,
    errorKind: String,
  ) {
    val slot = presentationSlots[index]
    recordPresentationPathFailure("presenter_${stage}_failed")
    slot.frame = null
    slot.completionReported = true
    presentationQueue.quarantinePresentation(index)
    reportPresentationCompletion(
      frame,
      swapped = false,
      swapWaitNs = 0L,
      swapReturnedNs = System.nanoTime(),
      errorCode = errorCode,
      errorKind = errorKind,
    )
    failPresentationPath(stage)
    resumePresentationDrain()
  }

  private fun schedulePresentationFencePoll(
    index: Int,
    slot: PresentationSlot,
    flushCommands: Boolean = false,
  ): Boolean {
    val fence = slot.consumerFence
    if (fence == 0L) return false
    if (slot.consumerFencePollScheduled) return true
    if (flushCommands) GLES20.glFlush()
    if (slot.consumerFenceStartedAtNs == 0L) slot.consumerFenceStartedAtNs = System.nanoTime()
    slot.consumerFencePollScheduled = true
    val posted = presentationHandler.postDelayed(
      {
        try {
          pollPresentationFence(index, fence)
        } catch (error: Throwable) {
          failPresentationFence(index, fence, GLES30.GL_WAIT_FAILED)
        }
      },
      PRESENTATION_FENCE_POLL_DELAY_MS,
    )
    if (!posted) {
      slot.consumerFencePollScheduled = false
      failPresentationFence(index, fence, GLES30.GL_WAIT_FAILED)
    }
    return posted
  }

  private fun consumePresentationGlError(): Int {
    val firstError = GLES20.glGetError()
    var errorCount = if (firstError == GLES20.GL_NO_ERROR) 0 else 1
    while (errorCount < 16 && GLES20.glGetError() != GLES20.GL_NO_ERROR) errorCount++
    return firstError
  }

  private fun handlePresenterException(error: Throwable) {
    recordPresentationPathFailure("presenter_exception")
    logFlowRecordOnce(
      "presentation-handler-exception",
      Log.ERROR,
      "event=presentation_handoff state=failed stage=presenter_exception " +
        "exception=${error.javaClass.simpleName} reason=${error.message.orEmpty().replace(' ', '_').take(160)}",
      error,
    )
    val index = presenterCurrentSlotIndex
    if (index >= 0 && presentationQueue.state(index) == Media3FlowPresentationQueue.State.PRESENTING) {
      val slot = presentationSlots[index]
      val frame = slot.frame
      if (slot.consumerFence != 0L) {
        if (!slot.completionReported && frame != null) {
          slot.completionReported = true
          reportPresentationCompletion(
            frame,
            swapped = false,
            swapWaitNs = 0L,
            swapReturnedNs = System.nanoTime(),
            errorCode = 0,
            errorKind = "presenter_gles_exception",
          )
        }
        schedulePresentationFencePoll(index, slot)
      } else {
        slot.frame = null
        slot.completionReported = true
        presentationQueue.quarantinePresentation(index)
        if (frame != null) {
          reportPresentationCompletion(
            frame,
            swapped = false,
            swapWaitNs = 0L,
            swapReturnedNs = System.nanoTime(),
            errorCode = 0,
            errorKind = "presenter_gles_exception",
          )
        }
      }
    }
    failPresentationPath("presenter_exception")
    resumePresentationDrain()
  }

  private fun resumePresentationDrain() {
    presentationDrainPending.set(false)
    if (presentationQueue.hasReady()) schedulePresentationDrain()
  }

  private fun pollPresentationFence(index: Int, fence: Long) {
    val slot = presentationSlots[index]
    if (slot.consumerFence != fence || fence == 0L) return
    slot.consumerFencePollScheduled = false
    val waitResult = runCatching { GLES30.glClientWaitSync(fence, 0, 0L) }
      .getOrDefault(GLES30.GL_WAIT_FAILED)
    when (waitResult) {
      GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> {
        GLES30.glDeleteSync(fence)
        slot.consumerFence = 0L
        slot.consumerFenceStartedAtNs = 0L
        slot.frame = null
        slot.completionReported = false
        presentationQueue.finishPresentation(index)
        when {
          disposed -> finishPresentationThreadRelease()
          presentationFailed && !presentationQueue.hasPresenting() -> destroyPresentationContextOnThread()
        }
      }
      GLES30.GL_TIMEOUT_EXPIRED -> {
        val elapsedNs = System.nanoTime() - slot.consumerFenceStartedAtNs
        if (slot.consumerFenceStartedAtNs != 0L && elapsedNs >= PRESENTATION_FENCE_TIMEOUT_NS) {
          failPresentationFence(index, fence, waitResult)
        } else {
          schedulePresentationFencePoll(index, slot)
        }
      }
      else -> failPresentationFence(index, fence, waitResult)
    }
  }

  private fun failPresentationFence(index: Int, fence: Long, waitResult: Int) {
    val slot = presentationSlots[index]
    if (slot.consumerFence != fence || fence == 0L) return
    val frame = slot.frame
    recordPresentationPathFailure("presenter_consumer_fence_wait_failed")
    runCatching { GLES30.glDeleteSync(fence) }
    slot.consumerFence = 0L
    slot.consumerFenceStartedAtNs = 0L
    slot.consumerFencePollScheduled = false
    slot.frame = null
    presentationQueue.quarantinePresentation(index)
    logFlowRecordOnce(
      "presentation-consumer-fence-wait-failed",
      Log.ERROR,
      "event=presentation_handoff state=failed stage=consumer_fence_wait " +
        "reason=slot_quarantined wait_result=0x${waitResult.toString(16)}",
    )
    if (!slot.completionReported && frame != null) {
      slot.completionReported = true
      reportPresentationCompletion(
        frame,
        swapped = false,
        swapWaitNs = 0L,
        swapReturnedNs = System.nanoTime(),
        errorCode = waitResult,
        errorKind = "presenter_consumer_fence_wait",
      )
    }
    failPresentationPath("consumer_fence_wait")
  }

  private fun failPresentationPath(stage: String) {
    val firstFailure = !presentationFailed
    presentationFailed = true
    presentationSurfaceReady = false
    outputAvailable = false
    cancelPendingPresentationBuffersOnPresentationThread()
    logFlowRecordOnce(
      "presentation-path-failed",
      Log.ERROR,
      "event=presentation_handoff state=failed stage=$stage fallback=disabled_after_gpu_ownership_failure",
    )
    if (!presentationQueue.hasPresenting()) destroyPresentationContextOnThread()
    if (firstFailure && !disposed) reportError(IllegalStateException("Media3 Flow presentation failed at $stage"))
    if (disposed && !presentationQueue.hasPresenting()) finishPresentationThreadRelease()
  }

  private fun discardPresentationSlot(index: Int) {
    val slot = presentationSlots[index]
    if (slot.producerFence != 0L) GLES30.glDeleteSync(slot.producerFence)
    slot.producerFence = 0L
    slot.consumerFenceStartedAtNs = 0L
    slot.consumerFencePollScheduled = false
    slot.frame = null
    slot.completionReported = false
    presentationQueue.finishPresentation(index)
  }

  private fun cancelPendingPresentationBuffersOnPresentationThread() {
    val canDeleteProducerFences = makePresentationPbufferCurrent()
    presentationQueue.cancelReady().forEach { index ->
      val slot = presentationSlots[index]
      if (canDeleteProducerFences) {
        if (slot.producerFence != 0L) GLES30.glDeleteSync(slot.producerFence)
        slot.producerFence = 0L
      }
      slot.consumerFencePollScheduled = false
      slot.frame = null
    }
  }

  private fun reportPresentationCompletion(
    frame: PresentationFrame,
    swapped: Boolean,
    swapWaitNs: Long,
    swapReturnedNs: Long,
    errorCode: Int,
    errorKind: String = "presenter_egl",
    swapAttempted: Boolean = false,
  ) {
    glHandler.post {
      if (disposed || frame.streamGeneration != streamGeneration ||
        frame.surfaceGeneration != outputSurfaceGeneration.get()
      ) return@post
      if (swapAttempted) recordCpuDuration(FlowCpuStage.EGL_SWAP_WAIT, swapWaitNs)
      val outputState: String
      val outputReason: String
      if (swapped) {
        outputWallTimes.addLast(swapReturnedNs)
        while (outputWallTimes.size > OUTPUT_RATE_WINDOW) outputWallTimes.removeFirst()
        if (frame.synthesized) {
          generatedFrames++
          generatedWallTimes.addLast(swapReturnedNs)
          while (generatedWallTimes.size > OUTPUT_RATE_WINDOW) generatedWallTimes.removeFirst()
        }
        lastRenderedPtsUs = max(lastRenderedPtsUs, frame.targetPtsUs)
        if (!firstFrameReported) {
          firstFrameReported = true
          dispatchListener { it.onFirstFrameRendered() }
        }
        metadataListener?.onVideoFrameAboutToBeRendered(
          frame.targetPtsUs,
          frame.renderTimeNs,
          frame.format ?: Format.Builder().build(),
          null,
        )
        outputState = if (frame.synthesized) "synthesized" else "passthrough"
        outputReason = frame.reason
      } else {
        val failureReason = "${errorKind}_failed"
        val failureDetail = "${failureReason}_0x${errorCode.toString(16)}"
        recordOutputSubmissionFailure(failureReason, detail = failureDetail)
        droppedFrames.incrementAndGet()
        dispatchListener { it.onFrameDropped() }
        outputState = "output_submission_failed"
        outputReason = failureDetail
      }
      outputDecisionCounters.record(outputState, outputReason)
      logPresentedOutput(frame, outputState, outputReason)
      publishDiagnostics(lastState, lastBypassReason)
    }
  }

  private fun logPresentedOutput(frame: PresentationFrame, outputState: String, outputReason: String) {
    val signature = "$outputState|$outputReason"
    val nowNs = System.nanoTime()
    if (signature == lastLoggedOutputDiagnosticSignature &&
      lastOutputDiagnosticLogAtNs != 0L &&
      nowNs - lastOutputDiagnosticLogAtNs < FLOW_OUTPUT_DIAGNOSTIC_LOG_MIN_INTERVAL_NS &&
      outputState != "output_submission_failed"
    ) return
    lastLoggedOutputDiagnosticSignature = signature
    lastOutputDiagnosticLogAtNs = nowNs
    val alpha = frame.interpolationAlpha
    val outputMessage =
      "MPVFLOW_DIAGNOSTIC component=native-media3 event=output state=$outputState renderer_api=vulkan " +
        "requested_compute_api=${requestedComputeApi.name.lowercase()} " +
        "effective_compute_api=${effectiveComputeApiName()} " +
        "reason=$outputReason target_fps=$targetFps speed=${formatLogFloat(frame.speed)} " +
        "source_pts_us=${frame.sourcePtsUs} source_pts0_us=${frame.sourcePts0Us ?: "none"} " +
        "source_pts1_us=${frame.sourcePts1Us ?: "none"} target_pts_us=${frame.targetPtsUs} " +
        "interpolation_alpha=${alpha?.let(::formatLogFloat) ?: "none"} " +
        "fallback_source_frame_pts_us=${if (outputState == "passthrough") frame.sourcePtsUs else "none"} " +
        "timestamp_source=media3_decoder_pts sync_mode=1x"
    Log.println(if (outputState == "synthesized") Log.INFO else Log.WARN, logTag, outputMessage)
  }

  private fun makePresentationWindowCurrent(): Boolean {
    if (windowSurface == EGL14.EGL_NO_SURFACE || presentationEglContext == EGL14.EGL_NO_CONTEXT ||
      eglDisplay == EGL14.EGL_NO_DISPLAY
    ) return false
    if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, presentationEglContext)) {
      logFlowRecordOnce(
        "egl-make-presentation-window-current",
        Log.ERROR,
        "event=egl_context state=failed stage=presentation_eglMakeCurrent egl_error=0x${EGL14.eglGetError().toString(16)}",
      )
      return false
    }
    if (!eglSwapIntervalAttempted) {
      eglSwapIntervalAttempted = true
      eglSwapIntervalZeroApplied = EGL14.eglSwapInterval(eglDisplay, 0)
      if (!eglSwapIntervalZeroApplied) {
        val error = EGL14.eglGetError()
        val restored = EGL14.eglSwapInterval(eglDisplay, 1)
        Log.w(logTag, "Unable to disable implicit EGL swap pacing; preserving interval=1 restored=$restored egl_error=0x${error.toString(16)}")
      }
    }
    return true
  }

  private fun makePresentationPbufferCurrent(): Boolean {
    if (eglDisplay == EGL14.EGL_NO_DISPLAY || presentationEglContext == EGL14.EGL_NO_CONTEXT ||
      presentationPbufferSurface == EGL14.EGL_NO_SURFACE
    ) return false
    return EGL14.eglMakeCurrent(
      eglDisplay,
      presentationPbufferSurface,
      presentationPbufferSurface,
      presentationEglContext,
    )
  }

  private fun ensurePresentationEgl(): Boolean {
    if (presentationEglContext != EGL14.EGL_NO_CONTEXT && presentationBlitProgram != 0) {
      return makePresentationPbufferCurrent()
    }
    if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT || eglConfig == null) return false
    val attributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
    presentationEglContext = EGL14.eglCreateContext(eglDisplay, checkNotNull(eglConfig), eglContext, attributes, 0)
    if (presentationEglContext == EGL14.EGL_NO_CONTEXT) {
      Log.e(logTag, "Unable to create shared presentation EGL context error=0x${EGL14.eglGetError().toString(16)}")
      return false
    }
    presentationPbufferSurface = EGL14.eglCreatePbufferSurface(
      eglDisplay,
      checkNotNull(eglConfig),
      intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
      0,
    )
    if (presentationPbufferSurface == EGL14.EGL_NO_SURFACE || !makePresentationPbufferCurrent()) {
      Log.e(logTag, "Unable to create or activate presentation EGL pbuffer error=0x${EGL14.eglGetError().toString(16)}")
      destroyPresentationContextOnThread()
      return false
    }
    presentationBlitProgram = linkProgram("async_presentation_blit", COPY_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
    presentationBlitImageLocation = if (presentationBlitProgram != 0) GLES20.glGetUniformLocation(presentationBlitProgram, "uImage") else -1
    presentationBlitTextureScaleLocation = if (presentationBlitProgram != 0) GLES20.glGetUniformLocation(presentationBlitProgram, "uTextureScale") else -1
    presentationBlitTextureOffsetLocation = if (presentationBlitProgram != 0) GLES20.glGetUniformLocation(presentationBlitProgram, "uTextureOffset") else -1
    presentationBlitRotationLocation = if (presentationBlitProgram != 0) GLES20.glGetUniformLocation(presentationBlitProgram, "uRotationDegrees") else -1
    val ready = presentationBlitProgram != 0 && presentationBlitImageLocation >= 0 &&
      presentationBlitTextureScaleLocation >= 0 && presentationBlitTextureOffsetLocation >= 0 &&
      presentationBlitRotationLocation >= 0
    if (!ready) destroyPresentationContextOnThread()
    return ready
  }

  private fun destroyPresentationContextOnThread() {
    destroyWindowSurfaceOnPresentationThread()
    if (presentationBlitProgram != 0) GLES20.glDeleteProgram(presentationBlitProgram)
    presentationBlitProgram = 0
    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
      if (presentationPbufferSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, presentationPbufferSurface)
      if (presentationEglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, presentationEglContext)
    }
    presentationPbufferSurface = EGL14.EGL_NO_SURFACE
    presentationEglContext = EGL14.EGL_NO_CONTEXT
  }

  private fun createWindowSurface() {
    if (!presentationHandler.post { createWindowSurfaceOnPresentationThread() }) {
      presentationSurfaceReady = false
    }
  }

  private fun createWindowSurfaceOnPresentationThread() {
    if (presentationFailed) {
      presentationSurfaceReady = false
      return
    }
    if (presentationQueue.hasPresenting()) {
      presentationHandler.postDelayed(
        { createWindowSurfaceOnPresentationThread() },
        PRESENTATION_FENCE_POLL_DELAY_MS,
      )
      return
    }
    destroyWindowSurfaceOnPresentationThread()
    val surface = outputSurface ?: return
    if (!Media3FlowOutputSurfacePolicy.shouldCreateEglWindowSurface(
        sinkInitialized = initialized,
        outputSurfaceValid = surface.isValid,
        disposed = disposed,
      ) || !ensurePresentationEgl()
    ) return
    windowSurface = EGL14.eglCreateWindowSurface(
      eglDisplay,
      checkNotNull(eglConfig),
      surface,
      intArrayOf(EGL14.EGL_NONE),
      0,
    )
    if (windowSurface == EGL14.EGL_NO_SURFACE) {
      outputAvailable = false
      presentationSurfaceReady = false
      Log.e(logTag, "Unable to create Media3 Flow EGL window surface error=0x${EGL14.eglGetError().toString(16)}")
      reportError(IllegalStateException("Unable to create Media3 Flow EGL window surface"))
      return
    }
    presentationSurfaceReady = true
    setOutputFrameRate()
    currentFormat?.let { format -> dispatchListener { it.onVideoSizeChanged(videoSize(format)) } }
    if (presentationQueue.hasReady()) schedulePresentationDrain()
  }

  private fun destroyWindowSurface() {
    presentationSurfaceReady = false
    presentationHandler.post { destroyWindowSurfaceOnPresentationThread() }
  }

  private fun destroyWindowSurfaceOnPresentationThread() {
    presentationSurfaceReady = false
    if (presentationQueue.hasPresenting()) {
      presentationHandler.postDelayed(
        { destroyWindowSurfaceOnPresentationThread() },
        PRESENTATION_FENCE_POLL_DELAY_MS,
      )
      return
    }
    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
      windowSurface = EGL14.EGL_NO_SURFACE
      return
    }
    makePresentationPbufferCurrent()
    if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
    windowSurface = EGL14.EGL_NO_SURFACE
    eglSwapIntervalAttempted = false
    eglSwapIntervalZeroApplied = false
  }

  private fun drawPresentationFullscreenTriangle() {
    presentationVertexBuffer.position(0)
    GLES20.glEnableVertexAttribArray(0)
    GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, presentationVertexBuffer)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    GLES20.glDisableVertexAttribArray(0)
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
    GLES20.glUniform1i(copyExternalLocation, 0)
    checkGlError("input copy uniform uExternal", frameProbeOnly = true)
    GLES20.glUniformMatrix4fv(copyTextureMatrixLocation, 1, false, transform, 0)
    checkGlError("input copy uniform uTextureMatrix", frameProbeOnly = true)
    GLES20.glUniform2f(
      copyDownscaleFactorLocation,
      (decoderInputWidth.toFloat() / frameWidth).coerceAtLeast(1f),
      (decoderInputHeight.toFloat() / frameHeight).coerceAtLeast(1f),
    )
    checkGlError("input copy uniform uDownscaleFactor", frameProbeOnly = true)
    GLES20.glUniform2f(copyOutputTexelSizeLocation, 1f / frameWidth, 1f / frameHeight)
    checkGlError("input copy uniform uOutputTexelSize", frameProbeOnly = true)
    val format = currentFormat
    val convertBt2020ToBt709 = format != null && Media3FlowFormatPolicy.shouldConvertBt2020ToBt709(
      inputColorSpace = format.colorInfo?.colorSpace ?: Format.NO_VALUE,
      outputColorStandard = decoderOutputColorStandard,
      mimeType = format.sampleMimeType.orEmpty(),
      codecs = format.codecs.orEmpty(),
    )
    GLES20.glUniform1i(copyBt2020ToBt709Location, if (convertBt2020ToBt709) 1 else 0)
    checkGlError("input copy uniform uConvertBt2020ToBt709", frameProbeOnly = true)
    drawFullscreenTriangle()
    checkGlError("input copy draw", frameProbeOnly = true)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    checkGlError("input copy unbind framebuffer", frameProbeOnly = true)
  }

  private fun makeSharedImageExternal(slot: FrameSlot, stage: String): Boolean {
    if (flowVulkanContext == 0L || slot.nativeImageHandle == 0L) return true
    val returned = runCatching {
      Media3FlowVulkanNative.nativeHoldImageForExternal(flowVulkanContext, slot.nativeImageHandle)
    }.getOrDefault(false)
    if (!returned) {
      logFlowRecordOnce(
        "shared-image-external-handoff-$stage",
        Log.ERROR,
        "event=ownership state=failed stage=$stage image_role=input source_pts_us=${slot.ptsUs} " +
          "fallback=drop_frame_before_gles_access",
      )
    }
    return returned
  }

  private fun prepareSharedFrame(slot: FrameSlot) {
    check(flowVulkanContext != 0L && slot.nativeImageHandle != 0L) {
      "Media3 Flow frame is not backed by a Vulkan-shared HardwareBuffer"
    }
    // The JNI bridge exports the GLES native fence and makes Vulkan wait on it
    // asynchronously. The source AHardwareBuffer stays Vulkan-owned until the
    // display path reads it or the capture path reuses it.
    val prepareStartNs = System.nanoTime()
    try {
      check(Media3FlowVulkanNative.nativePrepareFrame(flowVulkanContext, slot.nativeImageHandle)) {
        "Vulkan could not stage the shared decoder frame"
      }
    } finally {
      recordCpuDuration(FlowCpuStage.VULKAN_INPUT_PREPARE, (System.nanoTime() - prepareStartNs).coerceAtLeast(0L))
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
    return Media3FlowCadence.checkedSubtract(positionUs, lookBehindUs) ?: Long.MIN_VALUE
  }

  private fun ensureProcessingResources(decoderWidth: Int, decoderHeight: Int) {
    val frameSize = Media3FlowGeometry.processingFrameSize(decoderWidth, decoderHeight)
    val sourceWidth = frameSize.width
    val sourceHeight = frameSize.height
    val motionSize = Media3FlowGeometry.motionSize(sourceWidth, sourceHeight, maxDimension)
    val width = motionSize.width
    val height = motionSize.height
    if (decoderWidth == decoderInputWidth && decoderHeight == decoderInputHeight &&
      sourceWidth == frameWidth && sourceHeight == frameHeight &&
      width == processingWidth && height == processingHeight && framePool.isNotEmpty() &&
      flowAvailable
    ) return
    clearSourceFrames()
    destroyFrameResources()
    decoderInputWidth = decoderWidth
    decoderInputHeight = decoderHeight
    frameWidth = sourceWidth
    frameHeight = sourceHeight
    processingWidth = width
    processingHeight = height
    val motionGridSize = Media3FlowGeometry.motionGridSize(width, height)
    gridWidth = motionGridSize.width
    gridHeight = motionGridSize.height
    if (flowVulkanContext == 0L && deviceSupportsVulkanFlow(appContext)) {
      flowVulkanContext = runCatching {
        Media3FlowVulkanNative.nativeCreateContext(maxDimension, requestedComputeApi.nativeValue)
      }.getOrDefault(0L)
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
        "event=renderer_init state=ready renderer_api=vulkan " +
          "requested_compute_api=${requestedComputeApi.name.lowercase()} " +
          "effective_compute_api=${effectiveComputeApiName()} target_fps=$targetFps " +
          "source=${decoderWidth}x${decoderHeight} frame_storage=${sourceWidth}x${sourceHeight} " +
          "motion=${width}x${height} input_images=${framePool.count { it.nativeImageHandle != 0L }} " +
          "required_input_images=$MAX_STORED_FRAMES output_image_present=${outputImageHandle != 0L} " +
          "hardware_decoder=media3 source_pts=media3_decoder_pts sync_mode=1x",
      )
    } else {
      lastBypassReason = "vulkan_shared_image_unavailable"
      logFlowRecordOnce(
        "shared-image-pool-failure-$sourceWidth-$sourceHeight",
        Log.ERROR,
        "event=renderer_init state=unavailable stage=shared_image_pool reason=vulkan_shared_image_unavailable " +
          "renderer_api=vulkan requested_compute_api=${requestedComputeApi.name.lowercase()} " +
          "effective_compute_api=${effectiveComputeApiName()} source=${decoderWidth}x${decoderHeight} " +
          "frame_storage=${sourceWidth}x${sourceHeight} motion=${width}x${height} " +
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
    deferredMotionPairs.clear()
    motionPairs.forEach {
      releaseMotionPair(it)
      it.available = false
      it.frame0PtsUs = C.TIME_UNSET
      it.frame1PtsUs = C.TIME_UNSET
    }
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
    copyExternalLocation = if (copyProgram != 0) GLES20.glGetUniformLocation(copyProgram, "uExternal") else -1
    copyTextureMatrixLocation = if (copyProgram != 0) GLES20.glGetUniformLocation(copyProgram, "uTextureMatrix") else -1
    copyDownscaleFactorLocation = if (copyProgram != 0) GLES20.glGetUniformLocation(copyProgram, "uDownscaleFactor") else -1
    copyOutputTexelSizeLocation = if (copyProgram != 0) GLES20.glGetUniformLocation(copyProgram, "uOutputTexelSize") else -1
    copyBt2020ToBt709Location = if (copyProgram != 0) GLES20.glGetUniformLocation(copyProgram, "uConvertBt2020ToBt709") else -1
    blitImageLocation = if (blitProgram != 0) GLES20.glGetUniformLocation(blitProgram, "uImage") else -1
    blitTextureScaleLocation = if (blitProgram != 0) GLES20.glGetUniformLocation(blitProgram, "uTextureScale") else -1
    blitTextureOffsetLocation = if (blitProgram != 0) GLES20.glGetUniformLocation(blitProgram, "uTextureOffset") else -1
    blitRotationLocation = if (blitProgram != 0) GLES20.glGetUniformLocation(blitProgram, "uRotationDegrees") else -1
    check(copyProgram != 0 && copyExternalLocation >= 0 && copyTextureMatrixLocation >= 0 &&
      copyDownscaleFactorLocation >= 0 && copyOutputTexelSizeLocation >= 0 &&
      copyBt2020ToBt709Location >= 0
    ) { "Media3 Flow input color-conversion shader did not compile completely" }
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
    presentationSlots.forEach { slot ->
      if (slot.producerFence != 0L) GLES30.glDeleteSync(slot.producerFence)
      if (slot.consumerFence != 0L) GLES30.glDeleteSync(slot.consumerFence)
      slot.producerFence = 0L
      slot.consumerFence = 0L
      slot.consumerFenceStartedAtNs = 0L
      slot.consumerFencePollScheduled = false
      deleteTexture(slot.textureId)
      slot.textureId = 0
      slot.width = 0
      slot.height = 0
      slot.frame = null
      slot.completionReported = false
    }
    if (flowVulkanContext != 0L) {
      diagnosticCaptureSession?.let { id -> runCatching { Media3FlowVulkanNative.nativeStopDiagnosticCapture(flowVulkanContext, id) } }
      diagnosticCaptureSession = null
      Media3FlowVulkanNative.nativeDestroyContext(flowVulkanContext)
      flowVulkanContext = 0L
    }
    intArrayOf(copyProgram, blitProgram)
      .filter { it != 0 }
      .forEach { GLES20.glDeleteProgram(it) }
    copyProgram = 0
    blitProgram = 0
    copyExternalLocation = -1
    copyTextureMatrixLocation = -1
    copyDownscaleFactorLocation = -1
    copyOutputTexelSizeLocation = -1
    copyBt2020ToBt709Location = -1
    blitImageLocation = -1
    blitTextureScaleLocation = -1
    blitTextureOffsetLocation = -1
    blitRotationLocation = -1
    if (gpuTimerQueryIds.isNotEmpty()) {
      GLES30.glDeleteQueries(gpuTimerQueryIds.size, gpuTimerQueryIds, 0)
    }
    gpuTimerQueryIds = IntArray(0)
    gpuTimerQueriesSupported = false
    pendingGpuTimerQueries.clear()
    activeGpuTimerQuery = null
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
    val queueSnapshot = inputQueueSnapshot(nowNs)
    val vulkanTiming = vulkanTimingSnapshot(nowNs)
    val outputFps = if (outputWallTimes.size >= 2) {
      val elapsedNs = outputWallTimes.last() - outputWallTimes.first()
      if (elapsedNs > 0L) (outputWallTimes.size - 1) * 1_000_000_000f / elapsedNs else 0f
    } else 0f
    val generatedFps = if (generatedWallTimes.size >= 2) {
      val elapsedNs = generatedWallTimes.last() - generatedWallTimes.first()
      if (elapsedNs > 0L) (generatedWallTimes.size - 1) * 1_000_000_000f / elapsedNs else 0f
    } else 0f
    val presentationFailures = presentationFailureCounters.snapshot()
    val diagnostics = Media3FlowDiagnostics(
      enabled = true,
      capturedSourceFps = Media3FlowCadence.estimateSourceFps(ptsHistory.toList()),
      capturedPtsSampleCount = ptsHistory.size,
      outputFps = outputFps,
      generatedFps = generatedFps,
      targetFps = targetFps,
      generatedFrames = generatedFrames,
      droppedFrames = droppedFrames.get(),
      skippedOutputTicks = skippedOutputTicks.get(),
      outputSubmissionFailures = outputSubmissionFailures.get(),
      lastOutputSubmissionFailureReason = presentationFailures.lastOutputSubmissionFailureReason,
      outputSubmissionFailureReasons = presentationFailures.outputSubmissionReasons,
      presentationPathFailureReasons = presentationFailures.presentationPathReasons,
      lastNoWritablePresentationSlotStates = presentationFailures.lastNoWritableSlotStates,
      skippedFrames = skippedFrames,
      motionEstimateSubmitMs = lastMotionSubmitMs,
      motionPassAverageGpuUs = vulkanTiming.motion.averageUs,
      confidence = null,
      motionGridWidth = gridWidth,
      motionGridHeight = gridHeight,
      processingWidth = processingWidth,
      processingHeight = processingHeight,
      frameStorageWidth = frameWidth,
      frameStorageHeight = frameHeight,
      state = state,
      bypassReason = reason,
      vulkanTimings = vulkanTiming,
      glesTimings = glesTimingSnapshot(),
      cpuTimings = cpuTimingSnapshot(),
      pixelCoverage = pixelCoverageSnapshot(),
      pendingInputDepth = queueSnapshot.depth,
      inputInFlight = queueSnapshot.inFlight,
      oldestPendingInputAgeMs = queueSnapshot.oldestAgeMs,
      sourceFrameDepth = sourceFrames.size,
      readyMotionPairDepth = motionPairs.count { it.available },
      presentationQueueDepth = presentationQueue.readyCount(),
      presentationInFlight = presentationQueue.presentingCount(),
      presentationCoalescedFrames = presentationQueue.coalescedCount(),
      selectedSourcePtsAgeMs = lastSelectedSourcePtsAgeMs,
      sourcePairPtsGapMs = lastSourcePairPtsGapMs,
      frame0CaptureAgeMs = lastFrame0CaptureAgeMs,
      frame1CaptureAgeMs = lastFrame1CaptureAgeMs,
      outputCallbackLatenessMs = lastOutputCallbackLatenessMs,
      lastOutputTickGapReason = lastOutputTickGapReason,
      outputTickGapContexts = outputTickGapContexts.toMap(),
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

    val sourceFormat = currentFormat
    val inputSize = sourceFormat?.let { "${it.width}x${it.height}" } ?: "unknown"
    val motionGridStepSourcePxEstimate =
      sourceFormat?.let {
        estimateFlowMotionGridStepSourcePx(
          it.width,
          it.height,
          diagnostics.processingWidth,
          diagnostics.processingHeight,
        )
      }?.let {
        String.format(java.util.Locale.US, "%.1fx%.1f", it.first, it.second)
      } ?: "unavailable"
    val glesTiming = diagnostics.glesTimings
    val cpuTiming = diagnostics.cpuTimings
    val vulkanTiming = diagnostics.vulkanTimings
    val presentationFailures = presentationFailureCounters.snapshot()
    val gpuElapsed = diagnostics.motionPassAverageGpuUs?.let {
      "${String.format(java.util.Locale.US, "%.2f", it)}us_per_motion_pass"
    }
      ?: vulkanTiming.motion.status
    val confidence = diagnostics.confidence?.let { formatLogFloat(it) } ?: "n/a"
    val vulkanStages = "preprocess=${formatVulkanStage(vulkanTiming.preprocess)} " +
      "analysis=${formatVulkanStage(vulkanTiming.analysis)} " +
      "motionFine=${formatVulkanStage(vulkanTiming.motionFine)} " +
      "motionMiddle=${formatVulkanStage(vulkanTiming.motionMiddle)} " +
      "motionCoarse=${formatVulkanStage(vulkanTiming.motionCoarse)} " +
      "motionTotal=${formatVulkanStage(vulkanTiming.motion)} " +
      "synthesis=${formatVulkanStage(vulkanTiming.synthesis)}"
    val glesStages = "presentationStagingCopy=${formatFlowDuration(glesTiming.presentationStagingCopy)}"
    val cpuStages = "media3InputQueue=${formatFlowDuration(cpuTiming.inputQueueWait)} " +
      "frameHandler=${formatFlowDuration(cpuTiming.frameHandlerCall)} " +
      "eglSwapWait=${formatFlowDuration(cpuTiming.eglSwapWait)} " +
      "inputPrepareSubmit=${formatFlowDuration(cpuTiming.vulkanInputPrepare)} " +
      "synthesisSubmit=${formatFlowDuration(cpuTiming.vulkanSynthesisSubmit)}"
    Log.i(
      logTag,
        "flow_summary ${BuildIdentityDiagnostic.shaField(BuildConfig.GIT_SHA)} " +
        "renderer_api=vulkan requested_compute_api=${requestedComputeApi.name.lowercase()} " +
        "effective_compute_api=${effectiveComputeApiName()} state=${diagnostics.state} " +
        "bypass=${diagnostics.bypassReason ?: "none"} " +
        "positionUs=$latestPositionUs speed=${formatLogFloat(playbackSpeed)} " +
        "capturedSourceFps=${formatLogFloat(diagnostics.capturedSourceFps)} " +
        "capturedPtsSamples=${diagnostics.capturedPtsSampleCount} targetFps=${diagnostics.targetFps} " +
        "eglSwapReturnFps=${formatLogFloat(diagnostics.outputFps)} " +
        "eglSwapInterval=${if (eglSwapIntervalZeroApplied) 0 else "default"} " +
        "outputMetric=successful_eglSwapBuffers_return_not_scanout " +
        "generatedFps=${formatLogFloat(diagnostics.generatedFps)} " +
        "generatedTotal=${diagnostics.generatedFrames} inputDropsTotal=${diagnostics.droppedFrames} " +
        "outputDecisionTotals=${outputDecisionCounters.snapshot().asLogFieldValue()} " +
        "motionPairAnalysisTotals=${motionPairAnalysisCounters.snapshot().asLogFieldValue()} " +
        "inferredSkippedOutputTicksTotal=${diagnostics.skippedOutputTicks} skippedTotal=${diagnostics.skippedFrames} " +
        "outputSubmissionFailures=${diagnostics.outputSubmissionFailures} " +
        "lastOutputSubmissionFailure=${diagnostics.lastOutputSubmissionFailureReason} " +
        "outputSubmissionFailureReasons=${presentationFailures.outputSubmissionReasonsAsLogFieldValue()} " +
        "presentationPathFailureReasons=${presentationFailures.presentationPathReasonsAsLogFieldValue()} " +
        "lastNoWritableSlotStates=${presentationFailures.lastNoWritableSlotStatesAsLogFieldValue()} " +
        "tickGapReason=${diagnostics.lastOutputTickGapReason} " +
        "tickGapContexts=${formatDeadlineReasons(diagnostics.outputTickGapContexts)} " +
        "motionDeadlineAdmission=cpu_submit_proxy_not_gpu_completion " +
        "queueDepth=pendingInput:${diagnostics.pendingInputDepth},inFlight:${diagnostics.inputInFlight}," +
        "sourceFrames:${diagnostics.sourceFrameDepth},readyMotionPairs:${diagnostics.readyMotionPairDepth} " +
        "presentationQueue=queued:${diagnostics.presentationQueueDepth},inFlight:${diagnostics.presentationInFlight}," +
        "coalesced:${diagnostics.presentationCoalescedFrames} " +
        "oldestPendingInputMs=${formatLogFloat(diagnostics.oldestPendingInputAgeMs)} " +
        "selectedSourcePtsAgeMs=${formatNullableLogFloat(diagnostics.selectedSourcePtsAgeMs)} " +
        "pairPtsGapMs=${formatNullableLogFloat(diagnostics.sourcePairPtsGapMs)} " +
        "pairCaptureAgeMs=${formatNullableLogFloat(diagnostics.frame0CaptureAgeMs)}/${formatNullableLogFloat(diagnostics.frame1CaptureAgeMs)} " +
        "outputCallbackLatenessMs=${formatLogFloat(diagnostics.outputCallbackLatenessMs)} " +
        "input=$inputSize frameStorage=${diagnostics.frameStorageWidth}x${diagnostics.frameStorageHeight} " +
        "processing=${diagnostics.processingWidth}x${diagnostics.processingHeight} " +
        "motionPyramid=quarter>half>full " +
        "motionGrid=${diagnostics.motionGridWidth}x${diagnostics.motionGridHeight} " +
        "motionGridStepSourcePxEstimate=$motionGridStepSourcePxEstimate artifactRiskProxy=spatial_sampling_only " +
        "motionSubmitCpuMs=${formatLogFloat(diagnostics.motionEstimateSubmitMs)} " +
        "motionPassVulkanAvg=$gpuElapsed vulkanTimerStatus=${vulkanTiming.status} " +
        "computeTimestampSupported=${vulkanTiming.timestampsSupported} " +
        "computeQueueFamily=${vulkanTiming.computeQueueFamily} " +
        "computeTimestampValidBits=${vulkanTiming.timestampValidBits} " +
        "timestampPeriodNs=${String.format(java.util.Locale.US, "%.6f", vulkanTiming.timestampPeriodNs)} " +
        "timestampComputeAndGraphics=${vulkanTiming.timestampComputeAndGraphics} " +
        "vulkanStagesUs={$vulkanStages} " +
        "glesTimerStatus=${glesTiming.status} glesQueryCounts=valid:${glesTiming.validResults}," +
        "pending:${glesTiming.pendingResults},disjoint:${glesTiming.disjointResultsDiscarded}," +
        "zero:${glesTiming.zeroDurationResults},skipped:${glesTiming.skippedBecausePoolFull} " +
        "glesStagesUs={$glesStages} " +
        "cpuStagesUs={$cpuStages} coverage=unavailable_vulkan_ssbo_readback_disabled " +
        "pixelArtifactQuality=unmeasured_vulkan_ssbo_readback_disabled " +
        "confidence=$confidence displayReportedHz=${formatLogFloat(displayRefreshRate(appContext))} " +
        "displayModeInitialHz=${formatLogFloat(displayHz)} surfaceHintFps=${formatLogFloat(outputFrameRateHintFps)} " +
        "matchContentPreference=$matchContentFrameRatePreferenceLabel " +
        "surfaceSwitchStrategy=$surfaceFrameRateChangeStrategyLabel " +
        "media3Strategy=${if (changeFrameRateStrategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF) "off" else "only_if_seamless"}",
    )
  }

  private fun formatVulkanStage(stage: FlowVulkanStageTiming): String {
    val average = stage.averageUs?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "n/a"
    val maximum = stage.maximumUs?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "n/a"
    return "${stage.status}:avg=${average}us,max=${maximum}us,samples=${stage.completedSamples}," +
      "unreportedOrDropped=${stage.pendingOrDropped}"
  }

  private fun formatNullableLogFloat(value: Float?): String = value?.let(::formatLogFloat) ?: "n/a"

  private fun formatDeadlineReasons(reasons: Map<String, Long>): String =
    reasons.entries.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }

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
    val sourceLeadUs = sourcePtsUs?.let {
      Media3FlowCadence.checkedSubtract(it, targetPtsUs)?.toString() ?: "overflow"
    } ?: "none"
    val surfaceTimestampDeltaNs = if (
      lastSurfaceTimestampNs != C.TIME_UNSET && lastFrameReleaseTimestampNs != C.TIME_UNSET
    ) Media3FlowCadence.checkedSubtract(lastSurfaceTimestampNs, lastFrameReleaseTimestampNs) ?: C.TIME_UNSET
    else C.TIME_UNSET
    Log.i(
      logTag,
      "clock playerPositionUs=$positionUs speed=$speed targetPtsUs=$targetPtsUs " +
        "targetOffsetUs=${Media3FlowCadence.checkedSubtract(targetPtsUs, positionUs)?.toString() ?: "overflow"} " +
        "sourcePtsUs=${sourcePtsUs ?: "none"} " +
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

  private fun <T> runOnPresentationThreadSync(timeoutMs: Long = GL_INIT_TIMEOUT_MS, block: () -> T): T {
    if (Looper.myLooper() == presentationThread.looper) return block()
    val latch = CountDownLatch(1)
    var result: Any? = null
    var failure: Throwable? = null
    check(presentationHandler.post {
      try {
        result = block()
      } catch (error: Throwable) {
        failure = error
      } finally {
        latch.countDown()
      }
    }) { "Media3 Flow presentation thread is shutting down" }
    check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
      "Timed out waiting for Media3 Flow presentation initialization"
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  companion object {
    private const val MAX_STORED_FRAMES = 3
    private const val MAX_MOTION_PAIRS = 2
    private const val MAX_PRESENTATION_BUFFERS = 2
  private const val PRESENTATION_FENCE_POLL_DELAY_MS = 1L
  private const val PRESENTATION_FENCE_TIMEOUT_NS = 5_000_000_000L
    private const val MAX_QUEUED_FRAMES = 5
    private const val MAX_INPUT_RELEASE_AHEAD_US = 100_000L
    private const val HISTORY_SIZE = 24
    private const val OUTPUT_RATE_WINDOW = 31
    private const val ANALYSIS_DEADLINE_RESERVE = 0.85f
    private const val METRICS_INTERVAL_NS = 500_000_000L
    private const val FLOW_SUMMARY_LOG_INTERVAL_NS = 5_000_000_000L
    private const val FLOW_STATE_LOG_MIN_INTERVAL_NS = 1_000_000_000L
    private const val FLOW_OUTPUT_DIAGNOSTIC_LOG_MIN_INTERVAL_NS = 250_000_000L
    private const val GL_INIT_TIMEOUT_MS = 5_000L
    private const val MAX_GPU_TIMER_QUERIES = 64
    private const val GPU_TIMING_HISTORY_SIZE = 90
    private const val CPU_TIMING_HISTORY_SIZE = 90
    private const val NO_FRAME_TIME_NANOS = Long.MIN_VALUE
    private const val GL_TIME_ELAPSED_EXT = 0x88BF
    private const val GL_GPU_DISJOINT_EXT = 0x8FBB
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x0040

    @JvmStatic
    fun supportsFormat(format: Format): Boolean =
      supportsFormat(format, Build.VERSION.SDK_INT)

    internal fun supportsFormat(format: Format, sdkInt: Int): Boolean {
      val supportedRotation = format.rotationDegrees == 0 || format.rotationDegrees == 90 ||
        format.rotationDegrees == 180 || format.rotationDegrees == 270
      if (format.width < 16 || format.height < 16 || !supportedRotation ||
        !format.pixelWidthHeightRatio.isFinite() || format.pixelWidthHeightRatio <= 0f ||
        format.drmInitData != null
      ) return false
      val mime = format.sampleMimeType.orEmpty().lowercase()
      val codecs = format.codecs.orEmpty().lowercase()
      if (!mime.startsWith("video/")) return false
      val colorInfo = format.colorInfo
      return Media3FlowFormatPolicy.supportsSdrInterpolation(
        mimeType = mime,
        codecs = codecs,
        colorSpace = colorInfo?.colorSpace ?: Format.NO_VALUE,
        colorRange = colorInfo?.colorRange ?: Format.NO_VALUE,
        colorTransfer = colorInfo?.colorTransfer ?: Format.NO_VALUE,
        lumaBitdepth = colorInfo?.lumaBitdepth ?: Format.NO_VALUE,
        chromaBitdepth = colorInfo?.chromaBitdepth ?: Format.NO_VALUE,
        hasHdrStaticInfo = colorInfo?.hdrStaticInfo != null,
        isEquivalentToAssumedSdrDefault = ColorInfo.isEquivalentToAssumedSdrDefault(colorInfo),
        decoderSdrRequestAvailable = sdkInt >= 31,
      )
    }

    @JvmStatic
    fun requiresSdrOutputTransferRequest(format: Format): Boolean {
      val colorInfo = format.colorInfo
      return Media3FlowFormatPolicy.requiresSdrOutputRequest(
        mimeType = format.sampleMimeType.orEmpty().lowercase(),
        codecs = format.codecs.orEmpty().lowercase(),
        colorSpace = colorInfo?.colorSpace ?: Format.NO_VALUE,
        colorRange = colorInfo?.colorRange ?: Format.NO_VALUE,
        colorTransfer = colorInfo?.colorTransfer ?: Format.NO_VALUE,
        lumaBitdepth = colorInfo?.lumaBitdepth ?: Format.NO_VALUE,
        chromaBitdepth = colorInfo?.chromaBitdepth ?: Format.NO_VALUE,
        hasHdrStaticInfo = colorInfo?.hdrStaticInfo != null,
      )
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
      uniform vec2 uDownscaleFactor;
      uniform vec2 uOutputTexelSize;
      uniform bool uConvertBt2020ToBt709;

      vec3 bt709ToLinear(vec3 encoded) {
        vec3 low = encoded / 4.5;
        vec3 high = pow(max((encoded + vec3(0.099)) / 1.099, vec3(0.0)), vec3(1.0 / 0.45));
        return mix(low, high, step(vec3(0.081), encoded));
      }

      vec3 linearToBt709(vec3 linearRgb) {
        vec3 low = linearRgb * 4.5;
        vec3 high = 1.099 * pow(max(linearRgb, vec3(0.0)), vec3(0.45)) - vec3(0.099);
        return mix(low, high, step(vec3(0.018), linearRgb));
      }

      void main() {
        int tapsX = int(clamp(ceil(uDownscaleFactor.x), 1.0, 4.0));
        int tapsY = int(clamp(ceil(uDownscaleFactor.y), 1.0, 4.0));
        vec4 source = vec4(0.0);
        float sampleCount = 0.0;
        for (int y = 0; y < 4; ++y) {
          for (int x = 0; x < 4; ++x) {
            if (x < tapsX && y < tapsY) {
              vec2 tap = (vec2(float(x) + 0.5, float(y) + 0.5) /
                          vec2(float(tapsX), float(tapsY)) - 0.5) * uOutputTexelSize;
              vec2 uv = (uTextureMatrix * vec4(vUv + tap, 0.0, 1.0)).xy;
              source += texture(uExternal, uv);
              sampleCount += 1.0;
            }
          }
        }
        source /= max(sampleCount, 1.0);
        if (uConvertBt2020ToBt709) {
          vec3 linear2020 = bt709ToLinear(source.rgb);
          mat3 bt2020ToBt709 = mat3(
            1.660491, -0.124550, -0.018151,
           -0.587641,  1.132900, -0.100579,
           -0.072850, -0.008349,  1.118730
          );
          vec3 linear709 = bt2020ToBt709 * linear2020;
          float luma = clamp(dot(linear709, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
          vec3 chroma = linear709 - vec3(luma);
          float gamutScale = 1.0;
          for (int channel = 0; channel < 3; ++channel) {
            if (chroma[channel] > 0.0 && linear709[channel] > 1.0) {
              gamutScale = min(gamutScale, (1.0 - luma) / chroma[channel]);
            } else if (chroma[channel] < 0.0 && linear709[channel] < 0.0) {
              gamutScale = min(gamutScale, luma / -chroma[channel]);
            }
          }
          vec3 gamutMapped709 = vec3(luma) + chroma * clamp(gamutScale, 0.0, 1.0);
          outColor = vec4(linearToBt709(max(gamutMapped709, vec3(0.0))), source.a);
        } else {
          outColor = source;
        }
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
      uniform int uRotationDegrees;
      void main() {
        vec2 sourceUv = vUv;
        if (uRotationDegrees == 90) sourceUv = vec2(1.0 - vUv.y, vUv.x);
        else if (uRotationDegrees == 180) sourceUv = vec2(1.0 - vUv.x, 1.0 - vUv.y);
        else if (uRotationDegrees == 270) sourceUv = vec2(vUv.y, 1.0 - vUv.x);
        outColor = texture(uImage, uTextureOffset + sourceUv * uTextureScale);
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

  @androidx.annotation.RequiresApi(33)
  private object Api33 {
    const val TRANSFER_HLG = android.hardware.DataSpace.TRANSFER_HLG
    const val TRANSFER_ST2084 = android.hardware.DataSpace.TRANSFER_ST2084
    const val TRANSFER_LINEAR = android.hardware.DataSpace.TRANSFER_LINEAR
    const val TRANSFER_SMPTE_170M = android.hardware.DataSpace.TRANSFER_SMPTE_170M
    const val TRANSFER_SRGB = android.hardware.DataSpace.TRANSFER_SRGB
    const val TRANSFER_GAMMA2_2 = android.hardware.DataSpace.TRANSFER_GAMMA2_2
    const val TRANSFER_GAMMA2_6 = android.hardware.DataSpace.TRANSFER_GAMMA2_6
    const val TRANSFER_GAMMA2_8 = android.hardware.DataSpace.TRANSFER_GAMMA2_8

    fun dataSpace(texture: SurfaceTexture): Int = texture.dataSpace

    fun transfer(dataSpace: Int): Int = android.hardware.DataSpace.getTransfer(dataSpace)

    fun colorSpace(dataSpace: Int): Int? = when (android.hardware.DataSpace.getStandard(dataSpace)) {
      android.hardware.DataSpace.STANDARD_BT2020,
      android.hardware.DataSpace.STANDARD_BT2020_CONSTANT_LUMINANCE -> C.COLOR_SPACE_BT2020
      android.hardware.DataSpace.STANDARD_BT709 -> C.COLOR_SPACE_BT709
      android.hardware.DataSpace.STANDARD_BT601_525,
      android.hardware.DataSpace.STANDARD_BT601_525_UNADJUSTED,
      android.hardware.DataSpace.STANDARD_BT601_625,
      android.hardware.DataSpace.STANDARD_BT601_625_UNADJUSTED -> C.COLOR_SPACE_BT601
      else -> null
    }
  }

  private fun displayRefreshRate(context: Context): Float = runCatching {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager.defaultDisplay.refreshRate
  }.getOrDefault(0f)
}
