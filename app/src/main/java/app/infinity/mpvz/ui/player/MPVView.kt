/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Environment
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.core.view.WindowInsetsCompat
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.domain.anime4k.Anime4KManager
import app.infinity.mpvz.domain.hdr.HdrToysManager
import app.infinity.mpvz.network.AndroidCookieJar
import app.infinity.mpvz.presentation.crash.DebugLogReader
import app.infinity.mpvz.presentation.crash.MpvFlowRuntimeCadenceSample
import app.infinity.mpvz.presentation.crash.latestMpvFlowRuntimeCadenceSample
import app.infinity.mpvz.presentation.crash.MpvFlowRuntimeCadenceSampleCache
import app.infinity.mpvz.presentation.crash.observedMpvFlowPassthroughReasons
import app.infinity.mpvz.preferences.AdvancedPreferences
import app.infinity.mpvz.preferences.AudioPreferences
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.DEFAULT_SUBTITLE_FONT_FAMILY
import app.infinity.mpvz.preferences.MpvConfigControlledFeatures
import app.infinity.mpvz.preferences.MpvConfigOverridePolicy
import app.infinity.mpvz.preferences.MpvFlowComputeApi
import app.infinity.mpvz.preferences.PlayerPreferences
import app.infinity.mpvz.preferences.effectiveMpvFlowMaxDimension
import app.infinity.mpvz.preferences.effectiveMpvFlowSearchRadius
import app.infinity.mpvz.preferences.effectiveMpvFlowTargetFps
import app.infinity.mpvz.preferences.SubtitlesPreferences
import app.infinity.mpvz.preferences.YtdlPreferences
import app.infinity.mpvz.ui.player.PlayerActivity.Companion.TAG
import app.infinity.mpvz.ui.player.anime4k.applyAnime4KShaderChain
import app.infinity.mpvz.ui.player.anime4k.applyAnime4KStabilityOptions
import app.infinity.mpvz.ui.player.anime4k.clearAnime4KShaders
import app.infinity.mpvz.ui.player.anime4k.selectRuntimeStableAnime4K
import app.infinity.mpvz.ui.player.controls.components.panels.toColorHexString
import app.infinity.mpvz.ui.player.ytdlp.YtdlpManager
import app.infinity.mpvz.utils.device.VulkanCapabilities
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.KeyMapping
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.reflect.KProperty
import kotlin.math.roundToInt

internal fun resolveSurfaceFrameRateHint(
  sourceFrameRate: Double?,
  interpolationFrameRate: Double?,
): Double? =
  interpolationFrameRate?.takeIf { it.isFinite() && it > 0.0 }
    ?: sourceFrameRate?.takeIf { it.isFinite() && it > 0.0 }

internal fun resolveFlowDisplayRefreshRateHz(
  viewRefreshRateHz: Float?,
  displayManagerRefreshRateHz: Float?,
): Float? =
  sequenceOf(viewRefreshRateHz, displayManagerRefreshRateHz)
    .filterNotNull()
    .firstOrNull { it.isFinite() && it in 20f..360f }

private data class FlowDisplayRefreshRate(val hz: Float, val source: String)

class MPVView(
  context: Context,
  attributes: AttributeSet,
) : BaseMPVView(context, attributes),
  KoinComponent {
  private val audioPreferences: AudioPreferences by inject()
  private val playerPreferences: PlayerPreferences by inject()
  private val decoderPreferences: DecoderPreferences by inject()
  private val advancedPreferences: AdvancedPreferences by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val ytdlPreferences: YtdlPreferences by inject()
  private val anime4kManager: Anime4KManager by inject()
  private val hdrToysManager: HdrToysManager by inject()

  var isExiting = false
  var forceOpenGlFallback = false
  var isSurfaceReady = false
    private set
  var onSurfaceReady: (() -> Unit)? = null
  private var interpolationOutputFrameRate: Double? = null
  private val mpvFlowDiagnosticsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var mpvFlowDiagnosticsJob: Job? = null
  private val mpvFlowCaptureControlFile = java.io.File(context.cacheDir, "mpvflow-diagnostic-control.txt")
  private var mpvFlowCaptureControlConfigured = false
  private var mpvFlowCaptureSessionId: String? = null
  private var mpvFlowDiagnosticsEnabled = false
  private var mpvFlowOsdStatsEnabled = false
  @Volatile private var latestMpvFlowRuntimeSample: MpvFlowRuntimeCadenceSample? = null

  private fun currentDisplayRefreshRate(): FlowDisplayRefreshRate? {
    val viewDisplay = display
    val displayId = viewDisplay?.displayId ?: Display.DEFAULT_DISPLAY
    val managerDisplay = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
    val viewRate = viewDisplay?.refreshRate
    val managerRate = managerDisplay?.refreshRate
    val resolved = resolveFlowDisplayRefreshRateHz(viewRate, managerRate) ?: return null
    val source = if (viewRate != null && viewRate.isFinite() && viewRate in 20f..360f) {
      "attached_display"
    } else {
      "display_manager"
    }
    return FlowDisplayRefreshRate(resolved, source)
  }

  /**
   * Configures the process-wide player and binds this view as its current rendering surface.
   * Re-entering the player reuses the live core; it never creates a second native instance.
   */
  fun initializeSession(
    configDir: String,
    cacheDir: String,
  ): Result<Boolean> {
    // The libmpv core is process-wide, so returning to the player can reuse a core created with
    // older renderer preferences. Keep fallbacks stable for the lifetime of that preference
    // selection, but recreate the core when gpu-next/Vulkan selection actually changes.
    MpvConfigOverridePolicy.configure(advancedPreferences.mpvConfOverrides.get())
    val requestedBackend = selectRenderBackend(ignoreForcedOpenGlFallback = true)
    val frameInterpolationConfigOwned =
      MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.FRAME_INTERPOLATION)
    val mpvFlowRequested = decoderPreferences.mpvFlowFrameInterpolation.get()
    val displayRefreshRate = currentDisplayRefreshRate()
    val requestedMpvFlowTargetFps =
      effectiveMpvFlowTargetFps(
        decoderPreferences.mpvFlowTargetFps.get(),
        displayRefreshRate?.hz ?: 0f,
      )
    val requestedMpvFlowComputeApi = decoderPreferences.mpvFlowComputeApi.get()
    val requestedMpvFlowQuality = decoderPreferences.mpvFlowQuality.get().coerceIn(0, 100)
    val mpvFlowFrameInterpolationActive =
      mpvFlowRequested &&
        BuildConfig.MPV_HAS_MPVFLOW &&
        !frameInterpolationConfigOwned &&
        decoderPreferences.playbackEngine.get() != PlaybackEngineMode.NATIVE
    val mpvFlowSoftBlendDebugActive =
      BuildConfig.DEBUG &&
        decoderPreferences.mpvFlowSoftBlendDebug.get() &&
        mpvFlowFrameInterpolationActive &&
        requestedBackend.gpuApi == "vulkan" &&
        !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-soft-blend") &&
        !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-compute-api")
    val effectiveMpvFlowComputeApi =
      if (mpvFlowSoftBlendDebugActive) MpvFlowComputeApi.VULKAN else requestedMpvFlowComputeApi
    mpvFlowOsdStatsEnabled = mpvFlowFrameInterpolationActive
    mpvFlowDiagnosticsEnabled = BuildConfig.DEBUG && mpvFlowFrameInterpolationActive
    val coreConfigurationKey =
      "${requestedBackend.configurationKey}|conf=${MpvConfigOverridePolicy.configurationKey()}|" +
        "mpvflow=${if (mpvFlowFrameInterpolationActive) "$requestedMpvFlowTargetFps,compute_api=${effectiveMpvFlowComputeApi.nativeValue},quality=$requestedMpvFlowQuality,soft_blend=$mpvFlowSoftBlendDebugActive" else "off"}"
    val result =
      PlaybackSession.initialize(
        context = context.applicationContext,
        configDir = configDir,
        cacheDir = cacheDir,
        coreConfigurationKey = coreConfigurationKey,
        initOptions = ::initOptions,
        postInitOptions = ::postInitOptions,
        observeProperties = ::observeProperties,
      )
    // Refresh from the process-wide owner even when initOptions was skipped on core reuse.
    interpolationOutputFrameRate = PlaybackSession.surfaceFrameRateHintHz()
    if (result.isFailure) {
      mpvFlowOsdStatsEnabled = false
      mpvFlowDiagnosticsEnabled = false
    }
    if (result.isSuccess) {
      holder.removeCallback(this)
      holder.addCallback(this)
      if (holder.surface.isValid && !isSurfaceReady) surfaceCreated(holder)
    }
    updateMpvFlowDiagnostics()
    return result
  }

  fun releaseSurface() {
    stopFlowDiagnosticCapture()
    stopMpvFlowDiagnostics()
    holder.removeCallback(this)
    if (isSurfaceReady || PlaybackSession.state.value.surfaceAttached) {
      isSurfaceReady = false
      PlaybackSession.unbindSurface(this)
    }
  }

  private fun updateMpvFlowDiagnostics() {
    if ((mpvFlowOsdStatsEnabled || mpvFlowDiagnosticsEnabled) && isSurfaceReady && holder.surface.isValid) {
      startMpvFlowDiagnostics()
    } else {
      stopMpvFlowDiagnostics()
    }
  }

  private fun startMpvFlowDiagnostics() {
    if (!(mpvFlowOsdStatsEnabled || mpvFlowDiagnosticsEnabled) || !isSurfaceReady || mpvFlowDiagnosticsJob?.isActive == true) return
    mpvFlowDiagnosticsJob =
      mpvFlowDiagnosticsScope.launch {
        val sampleCache = MpvFlowRuntimeCadenceSampleCache()
        val observedFallbackReasons = linkedSetOf<String>()
        while (isActive && (mpvFlowOsdStatsEnabled || mpvFlowDiagnosticsEnabled) && isSurfaceReady) {
          val entries =
            withContext(Dispatchers.IO) {
              runCatching { DebugLogReader.readSnapshot(maxRawLines = 500).entries }
                .getOrDefault(emptyList())
            }
          val candidate = latestMpvFlowRuntimeCadenceSample(entries)
          val sample = sampleCache.update(candidate, System.currentTimeMillis())
          latestMpvFlowRuntimeSample = sample
          if (mpvFlowOsdStatsEnabled) sendMpvFlowOsdStats(sample)
          if (mpvFlowDiagnosticsEnabled && BuildConfig.DEBUG) {
            observedFallbackReasons += observedMpvFlowPassthroughReasons(entries)
            val healthFields =
              withContext(Dispatchers.IO) {
                runCatching { mpvFlowPlayerHealthFields(sample, observedFallbackReasons) }
                  .getOrDefault("status=unavailable")
              }
            Log.i(TAG, "MPVFLOW_DIAGNOSTIC component=mpvflow event=player_health engine=mpv $healthFields")
          }
          delay(3_000L)
        }
      }
  }

  private fun stopMpvFlowDiagnostics() {
    mpvFlowDiagnosticsJob?.cancel()
    mpvFlowDiagnosticsJob = null
    sendMpvFlowOsdStats(null)
  }

  private fun sendMpvFlowOsdStats(
    sample: MpvFlowRuntimeCadenceSample?,
  ) {
    if (!PlaybackSession.isInitialized) return
    val synthesizedFps = sample?.synthesizedSubmitFps
      ?.takeIf { it.isFinite() && it >= 0.0 }
      ?.toString()
      .orEmpty()
    runCatching {
      PlaybackSession.command(
        "script-message-to",
        "stats",
        "mpvflow-output-rate",
        synthesizedFps,
      )
    }
  }

  /** Latest app-side counters; successful swap submits are not proof of displayed/scanout FPS. */
  fun flowDiagnosticTimingSnapshot(): JSONObject {
    val sample = latestMpvFlowRuntimeSample
    fun nullable(value: Number?): Any = value ?: JSONObject.NULL
    return JSONObject()
      .put("schema_version", 1)
      .put("engine", "mpv")
      .put("sample_available", sample != null)
      .put("sample_time_ms", nullable(sample?.timeMillis))
      .put("sample_window_ms", nullable(sample?.windowMillis))
      .put("target_fps", nullable(sample?.targetFps))
      .put("synthesized_submit_fps", nullable(sample?.synthesizedSubmitFps))
      .put("successful_swap_submit_fps", nullable(sample?.swapSubmitFps))
      .put("render_callback_fps", nullable(sample?.renderCallbackFps))
      .put("synthesized_submits", nullable(sample?.synthesizedSubmits))
      .put("synthesized_render_callbacks", nullable(sample?.synthesizedRenderCallbacks))
      .put("render_failures", nullable(sample?.renderFailures))
      .put("swap_failures", nullable(sample?.swapFailures))
      .put("synthesis_rate_limited_callbacks", nullable(sample?.synthesisRateLimitedCallbacks))
      .put("frame_repeat_count", nullable(sample?.frameRepeatCount))
      .put("render_cpu_average_ms", nullable(sample?.renderCpuAverageMs))
      .put("render_cpu_maximum_ms", nullable(sample?.renderCpuMaximumMs))
      .put("pair_analysis_attempts", nullable(sample?.pairAnalysisAttempts))
      .put("pair_analysis_failures", nullable(sample?.pairAnalysisFailures))
      .put("source_sync_percent", nullable(sample?.sourceSyncPercent))
      .put("successful_swap_metric_semantics", "successful pl_swapchain submit; not SurfaceFlinger scanout")
      .put("surface_frame_rate_hint_hz", nullable(interpolationOutputFrameRate))
      .put("surface_frame_rate_hint_semantics", "requested Surface frame-rate hint; not observed presentation")
      .put("actual_displayed_fps_available", false)
      .put("actual_displayed_fps_source", "Perfetto FrameTimeline companion trace required for SurfaceView")
  }

  /** Arms the MPV VO through a private, atomically replaced control file. */
  fun startFlowDiagnosticCapture(sessionId: String, baseDirectory: String): String? {
    if (!BuildConfig.DEBUG || !BuildConfig.MPV_HAS_MPVFLOW) return "debug_mpvflow_unavailable"
    if (!mpvFlowDiagnosticsEnabled || !isSurfaceReady) return "mpvflow_not_active"
    if (!mpvFlowCaptureControlConfigured) return "mpvflow_capture_control_unavailable"
    if (!sessionId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return "invalid_session_id"
    val allowedRoot = java.io.File(context.cacheDir, "flow-diagnostics").canonicalFile
    val target = java.io.File(baseDirectory).canonicalFile
    if (target != allowedRoot) {
      return "capture_directory_outside_private_cache"
    }
    if (!target.isDirectory && !target.mkdirs()) return "capture_directory_create_failed"
    val error = writeFlowDiagnosticControl("START\t$sessionId\t${target.path}\n")
    if (error != null) return error
    mpvFlowCaptureSessionId = sessionId
    return null
  }

  fun stopFlowDiagnosticCapture(sessionId: String? = mpvFlowCaptureSessionId): String? {
    val activeSession = mpvFlowCaptureSessionId ?: return null
    if (sessionId == null || sessionId != activeSession) return "capture_session_id_mismatch"
    val error = writeFlowDiagnosticControl("STOP\t$sessionId\n")
    if (error == null) mpvFlowCaptureSessionId = null
    else Log.e(TAG, "MPVFLOW_DIAGNOSTIC component=capture event=control_write state=failed reason=$error")
    return error
  }

  private fun writeFlowDiagnosticControl(contents: String): String? =
    runCatching {
      val atomic = android.util.AtomicFile(mpvFlowCaptureControlFile)
      val output = atomic.startWrite()
      try {
        output.write(contents.toByteArray(Charsets.UTF_8))
        atomic.finishWrite(output)
      } catch (error: Exception) {
        atomic.failWrite(output)
        throw error
      }
    }.exceptionOrNull()?.let { "control_file_${it.javaClass.simpleName.lowercase()}" }


  private fun mpvFlowPlayerHealthFields(
    sample: MpvFlowRuntimeCadenceSample?,
    observedFallbackReasons: Set<String>,
  ): String {
    fun intProperty(name: String): Int? =
      runCatching { PlaybackSession.getPropertyInt(name) }.getOrNull()

    fun doubleProperty(name: String): Double? =
      runCatching { PlaybackSession.getPropertyDouble(name) }
        .getOrNull()
        ?.takeIf { it.isFinite() }

    fun stringProperty(name: String): String =
      runCatching { PlaybackSession.getPropertyString(name) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.replace(Regex("[^A-Za-z0-9_.+-]"), "_")
        ?: "unknown"

    fun metric(value: Double?): String =
      value?.takeIf { it.isFinite() }?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "unknown"

    val sourceWidth = intProperty("video-params/w")?.takeIf { it > 0 } ?: intProperty("width")?.coerceAtLeast(0) ?: 0
    val sourceHeight = intProperty("video-params/h")?.takeIf { it > 0 } ?: intProperty("height")?.coerceAtLeast(0) ?: 0
    val targetFps = sample?.targetFps ?: decoderPreferences.mpvFlowTargetFps.get()
    val quality = decoderPreferences.mpvFlowQuality.get().coerceIn(0, 100)
    val analysisCap = effectiveMpvFlowMaxDimension(targetFps, quality)
    val longestSourceDimension = maxOf(sourceWidth, sourceHeight)
    val processingScale =
      if (longestSourceDimension > 0) minOf(1.0, analysisCap.toDouble() / longestSourceDimension) else 0.0
    val processingWidth = (sourceWidth * processingScale).roundToInt()
    val processingHeight = (sourceHeight * processingScale).roundToInt()
    val motionGridStepSourcePx =
      estimateFlowMotionGridStepSourcePx(sourceWidth, sourceHeight, processingWidth, processingHeight)
        ?.let { "${metric(it.first)}x${metric(it.second)}" }
        ?: "unavailable"
    val avSyncMs = doubleProperty("avsync")?.times(1_000.0)
    val thermalStatus =
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        runCatching {
          context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus
        }.getOrNull()?.toString() ?: "unknown"
      } else {
        "unsupported"
      }
    val cadenceAgeMs = sample?.let { (System.currentTimeMillis() - it.timeMillis).coerceAtLeast(0L) }

    return "source=${sourceWidth}x$sourceHeight source_fps=${metric(doubleProperty("container-fps"))} " +
      "estimated_vf_fps=${metric(doubleProperty("estimated-vf-fps"))} " +
      "estimated_vf_fps_metric=mpv_filter_estimate_not_flow_submits " +
      "hwdec_current=${stringProperty("hwdec-current")} " +
      "decoder_drop_total=${intProperty("decoder-frame-drop-count") ?: "unknown"} " +
      "vo_drop_total=${intProperty("frame-drop-count") ?: "unknown"} " +
      "vo_delayed_total=${intProperty("vo-delayed-frame-count") ?: "unknown"} " +
      "display_fps=${metric(doubleProperty("display-fps"))} avsync_ms=${metric(avSyncMs)} " +
      "color_primaries=${stringProperty("video-params/primaries")} " +
      "color_transfer=${stringProperty("video-params/gamma")} " +
      "thermal_status=$thermalStatus analysis_cap_px=$analysisCap " +
      "analysis_processing_estimate=${processingWidth}x${processingHeight} " +
      "motion_grid_step_source_px_estimate=$motionGridStepSourcePx " +
      "flow_target_fps=${sample?.targetFps ?: targetFps} " +
      "render_callback_fps=${metric(sample?.renderCallbackFps)} " +
      "successful_synthesized_submit_fps=${metric(sample?.synthesizedSubmitFps)} " +
      "successful_all_swap_submit_fps=${metric(sample?.swapSubmitFps)} " +
      "cadence_window_ms=${metric(sample?.windowMillis)} cadence_sample_age_ms=${cadenceAgeMs ?: "unknown"} " +
      "synthesis_rate_limited_callbacks=${sample?.synthesisRateLimitedCallbacks ?: "unknown"} " +
      "synthesized_render_callbacks=${sample?.synthesizedRenderCallbacks ?: "unknown"} " +
      "frame_repeat_count=${sample?.frameRepeatCount ?: "unknown"} " +
      "render_failures=${sample?.renderFailures ?: "unknown"} swap_failures=${sample?.swapFailures ?: "unknown"} " +
      "pair_analysis_attempts=${sample?.pairAnalysisAttempts ?: "unknown"} " +
      "pair_analysis_failures=${sample?.pairAnalysisFailures ?: "unknown"} " +
      "source_sync_pct=${metric(sample?.sourceSyncPercent)} " +
      "render_cpu_avg_ms=${metric(sample?.renderCpuAverageMs)} render_cpu_max_ms=${metric(sample?.renderCpuMaximumMs)} " +
      "observed_passthrough_reasons=${observedFallbackReasons.sorted().joinToString(",").ifEmpty { "none_seen" }} " +
      "output_metric=successful_pl_swapchain_submit_not_scanout " +
      "pixelArtifactQuality=unmeasured_native_pixel_coverage_unavailable"
  }

  private data class RenderBackendSelection(
    val vo: String,
    val gpuApi: String,
    val gpuContext: String,
    val reason: String,
  ) {
    val configurationKey: String
      get() = "$vo|$gpuApi|$gpuContext"
  }

  fun getVideoOutAspect(): Double? {
    // Try to get aspect from video-params/aspect first
    val rawAspect = PlaybackSession.getPropertyDouble("video-params/aspect")
    val rotate = PlaybackSession.getPropertyInt("video-params/rotate") ?: 0

    // If aspect is not available or 0, calculate from width and height
    val finalAspect =
      if (rawAspect == null || rawAspect < 0.001) {
        val width =
          runCatching {
            PlaybackSession.getPropertyInt("width") ?: PlaybackSession.getPropertyInt("video-params/w") ?: 0
          }.getOrDefault(0)

        val height =
          runCatching {
            PlaybackSession.getPropertyInt("height") ?: PlaybackSession.getPropertyInt("video-params/h") ?: 0
          }.getOrDefault(0)

        if (width > 0 && height > 0) {
          width.toDouble() / height.toDouble()
        } else {
          null
        }
      } else {
        rawAspect
      }

    return finalAspect?.let { aspect ->
      if (aspect <= 0.001) {
        return null
      }
      val isRotated = (rotate % 180 == 90)
      val correctedAspect = if (isRotated) 1.0 / aspect else aspect
      correctedAspect
    }
  }

  class TrackDelegate(
    private val name: String,
  ) {
    operator fun getValue(
      thisRef: Any?,
      property: KProperty<*>,
    ): Int {
      val v = PlaybackSession.getPropertyString(name)
      // we can get null here for "no" or other invalid value
      return v?.toIntOrNull() ?: -1
    }

    operator fun setValue(
      thisRef: Any?,
      property: KProperty<*>,
      value: Int,
    ) {
      if (value == -1) {
        PlaybackSession.setPropertyString(name, "no")
      } else {
        PlaybackSession.setPropertyString(name, value.toString())
      }
    }
  }

  var sid: Int by TrackDelegate("sid")
  var secondarySid: Int by TrackDelegate("secondary-sid")
  var aid: Int by TrackDelegate("aid")

  override fun initOptions() {
    val profile = decoderPreferences.profile.get()
    PlaybackSession.setOptionString("profile", profile)
    val backend = selectRenderBackend()
    val useVulkan = backend.gpuApi == "vulkan"
    val hwdecMode = preferredHwdecMode(useVulkan)
    val mpvFlowFrameInterpolationPreference = decoderPreferences.mpvFlowFrameInterpolation.get()
    val displayRefreshRate = currentDisplayRefreshRate()
    val mpvFlowTargetFps =
      effectiveMpvFlowTargetFps(
        decoderPreferences.mpvFlowTargetFps.get(),
        displayRefreshRate?.hz ?: 0f,
      )
    val mpvFlowComputeApi = decoderPreferences.mpvFlowComputeApi.get()
    val mpvFlowQuality = decoderPreferences.mpvFlowQuality.get().coerceIn(0, 100)
    val mpvFlowMaxDimension = effectiveMpvFlowMaxDimension(mpvFlowTargetFps, mpvFlowQuality)
    val frameInterpolationConfigOwned =
      MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.FRAME_INTERPOLATION)
    val anime4kActive = decoderPreferences.enableAnime4K.get() && decoderPreferences.anime4kMode.get() != "OFF"
    val mpvFlowFrameInterpolationCandidate =
      mpvFlowFrameInterpolationPreference &&
        BuildConfig.MPV_HAS_MPVFLOW &&
        !frameInterpolationConfigOwned &&
        !anime4kActive &&
        RendererBackendPolicy.canUseDirectGpuFlow(backend.vo, backend.gpuApi) &&
        decoderPreferences.playbackEngine.get() != PlaybackEngineMode.NATIVE
    val flowDisplayFpsOverrideRc =
      when {
        mpvFlowFrameInterpolationCandidate && displayRefreshRate != null ->
          PlaybackSession.setOptionString("display-fps-override", displayRefreshRate.hz.toString())
        mpvFlowFrameInterpolationCandidate -> null
        !MpvConfigOverridePolicy.isOwnedByMpvConf("display-fps-override") ->
          PlaybackSession.setOptionString("display-fps-override", "0")
        else -> null
      }
    // Flow consumes gpu-next's before/after frame mix and needs a display-timed output tick between
    // source PTS values. Keep this scoped to the Flow path; the config-ownership gate above prevents
    // overriding an explicit mpv.conf interpolation or video-sync choice.
    val flowInterpolationRc =
      if (mpvFlowFrameInterpolationCandidate) {
        PlaybackSession.setOptionString("interpolation", "yes")
      } else {
        null
      }
    val flowVideoSyncRc =
      if (mpvFlowFrameInterpolationCandidate) {
        PlaybackSession.setOptionString("video-sync", "display-resample")
      } else {
        null
      }
    val flowTimingOptionsReady =
      flowInterpolationRc == 0 &&
        flowVideoSyncRc == 0 &&
        flowDisplayFpsOverrideRc == 0 &&
        displayRefreshRate != null
    if (mpvFlowFrameInterpolationCandidate && !flowTimingOptionsReady) {
      val resetInterpolationRc = PlaybackSession.setOptionString("interpolation", "no")
      val resetVideoSyncRc = PlaybackSession.setOptionString("video-sync", "audio")
      val resetDisplayFpsOverrideRc = PlaybackSession.setOptionString("display-fps-override", "0")
      Log.e(
        TAG,
        "MPVFLOW_DIAGNOSTIC event=timing_config state=failed " +
          "interpolation_rc=$flowInterpolationRc video_sync_rc=$flowVideoSyncRc " +
          "display_fps_override_rc=${flowDisplayFpsOverrideRc ?: "unavailable"} " +
          "display_refresh_hz=${displayRefreshRate?.hz ?: "unavailable"} " +
          "rollback_interpolation_rc=$resetInterpolationRc rollback_video_sync_rc=$resetVideoSyncRc " +
          "rollback_display_fps_override_rc=$resetDisplayFpsOverrideRc " +
          "fallback=source_passthrough",
      )
    }
    val mpvFlowFrameInterpolationEnabled =
      mpvFlowFrameInterpolationCandidate && flowTimingOptionsReady
    mpvFlowOsdStatsEnabled = mpvFlowFrameInterpolationEnabled
    mpvFlowDiagnosticsEnabled = BuildConfig.DEBUG && mpvFlowFrameInterpolationEnabled
    if (mpvFlowFrameInterpolationEnabled) {
      Log.i(
        TAG,
        "MPVFLOW_DIAGNOSTIC event=timing_config state=applied " +
          "interpolation_rc=$flowInterpolationRc video_sync_rc=$flowVideoSyncRc " +
          "display_fps_override_rc=$flowDisplayFpsOverrideRc " +
          "display_refresh_hz=${displayRefreshRate?.hz} " +
          "display_refresh_source=${displayRefreshRate?.source} video_sync=display-resample",
      )
    }
    val frameInterpolationEnabled = mpvFlowFrameInterpolationEnabled
    interpolationOutputFrameRate =
      if (mpvFlowFrameInterpolationEnabled) mpvFlowTargetFps.toDouble() else null
    PlaybackSession.setSurfaceFrameRateHintHz(interpolationOutputFrameRate)
    // A new file can publish its source FPS after Surface callbacks; reassert the active FRC output rate.
    updateSurfaceFrameRateHint()
    val logLevel =
      MpvFlowLogConfig.messageLevelOption(
        mpvFlowEnabled = mpvFlowFrameInterpolationEnabled,
        frameInterpolationEnabled = frameInterpolationEnabled,
        verboseLogging = advancedPreferences.verboseLogging.get(),
      )
    val flowLogLevelApplyResult =
      if (mpvFlowFrameInterpolationEnabled) PlaybackSession.setOptionString("msg-level", logLevel) else null
    if (mpvFlowFrameInterpolationEnabled) {
      Log.i(
        TAG,
        "MPVFLOW_DIAGNOSTIC event=logger_config state=${if (flowLogLevelApplyResult == 0) "applied" else "failed"} " +
          "logger_policy=$logLevel parent_module=vo/gpu-next apply_rc=$flowLogLevelApplyResult",
      )
    }
    // These options are consumed while the VO creates its libplacebo context.
    // Apply them before `vo`; otherwise gpu-next can keep its default OpenGL GPU.
    val gpuApiApplyResult = PlaybackSession.setOptionString("gpu-api", backend.gpuApi)
    val gpuContextApplyResult = PlaybackSession.setOptionString("gpu-context", backend.gpuContext)
    // Mailbox replaces stale queued presentation images without reducing the requested output rate.
    // libplacebo falls back to FIFO on Android surfaces that do not support mailbox presentation.
    val flowSwapMode =
      if (BuildConfig.MPV_HAS_MPVFLOW && backend.gpuApi == "vulkan") {
        when {
          MpvConfigOverridePolicy.isOwnedByMpvConf("vulkan-swap-mode") -> null
          mpvFlowFrameInterpolationEnabled -> "mailbox"
          else -> "auto"
        }
      } else {
        null
      }
    val flowSwapModeApplyResult =
      flowSwapMode?.let { PlaybackSession.setOptionString("vulkan-swap-mode", it) }
    // libplacebo requests swapchain_depth + 1 images; depth 2 asks for triple buffering.
    val flowSwapchainDepthResult =
      if (mpvFlowFrameInterpolationEnabled && backend.gpuApi == "vulkan" &&
        !MpvConfigOverridePolicy.isOwnedByMpvConf("swapchain-depth")) {
        PlaybackSession.setOptionString("swapchain-depth", "2")
      } else null
    if (mpvFlowFrameInterpolationEnabled && backend.gpuApi == "vulkan") {
      val presentModeState =
        when {
          MpvConfigOverridePolicy.isOwnedByMpvConf("vulkan-swap-mode") -> "delegated"
          flowSwapModeApplyResult == 0 -> "requested"
          else -> "failed"
        }
      Log.i(
        TAG,
        "MPVFLOW_DIAGNOSTIC event=present_mode state=$presentModeState " +
          "request=${flowSwapMode ?: "mpv.conf"} result=${flowSwapModeApplyResult ?: "unchanged"} " +
          "fallback=FIFO_if_unsupported swapchain_depth_result=${flowSwapchainDepthResult ?: "unchanged"} target_fps=$mpvFlowTargetFps quality=$mpvFlowQuality",
      )
    }
    val rendererApplyResult = PlaybackSession.setVideoOutput(backend.vo)
    val gpuApiOwner = if (MpvConfigOverridePolicy.isOwnedByMpvConf("gpu-api")) "mpv.conf" else "app"
    val gpuContextOwner = if (MpvConfigOverridePolicy.isOwnedByMpvConf("gpu-context")) "mpv.conf" else "app"
    val rendererConfigState =
      when {
        gpuApiApplyResult != 0 || gpuContextApplyResult != 0 || rendererApplyResult != 0 -> "failed"
        gpuApiOwner == "mpv.conf" || gpuContextOwner == "mpv.conf" -> "delegated"
        else -> "applied"
      }
    Log.i(
      TAG,
      "MPVFLOW_DIAGNOSTIC event=renderer_config state=$rendererConfigState " +
        "requested_renderer=${backend.vo} requested_gpu_api=${backend.gpuApi} " +
        "requested_gpu_context=${backend.gpuContext} " +
        "option_order=gpu-api>gpu-context>vo gpu_api_rc=$gpuApiApplyResult " +
        "gpu_context_rc=$gpuContextApplyResult vo_rc=$rendererApplyResult " +
        "gpu_api_owner=$gpuApiOwner gpu_context_owner=$gpuContextOwner " +
        "effective_gpu_api=${PlaybackSession.getPropertyString("gpu-api") ?: "unavailable"} " +
        "effective_gpu_context=${PlaybackSession.getPropertyString("gpu-context") ?: "unavailable"} " +
        "effective_renderer=${PlaybackSession.getPropertyString("vo") ?: "unavailable"}",
    )

    val hdrScreenOutputEnabled = decoderPreferences.hdrScreenOutput.get()
    val isLinearAvailable = useVulkan && backend.vo == "gpu-next"
    val hdrScreenMode =
      if (!hdrScreenOutputEnabled) {
        HdrScreenMode.OFF
      } else {
        val mode = decoderPreferences.hdrScreenMode.get()
        if (mode == HdrScreenMode.LINEAR && !isLinearAvailable) {
          HdrScreenMode.defaultEnabledMode
        } else {
          mode
        }
      }
    val hdrPipelineReady = hdrScreenMode != HdrScreenMode.LINEAR || isLinearAvailable
    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
      applyHdrScreenOutputOptions(
        mode = hdrScreenMode,
        pipelineReady = hdrPipelineReady,
        boostSdrToHdr = decoderPreferences.boostSdrToHdr.get(),
      )
    }

    // Prefer direct MediaCodec AHardwareBuffer import when this APK supports it; other Vulkan
    // builds retain hardware decoding through MediaCodec-copy and upload the source frame to Vulkan.
    if (mpvFlowFrameInterpolationEnabled) {
      // Prefer direct MediaCodec/Vulkan frames when supported. The standard flavor uses
      // mediacodec-copy so hardware decoding remains enabled when direct Vulkan import is absent;
      // Flow still performs interpolation on Vulkan and never reads synthesized output back.
      val hardwareDecodeMode =
        RendererBackendPolicy.gpuFlowHwdecMode(
          hardwareDecodingEnabled = decoderPreferences.tryHWDecoding.get(),
          buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
        )
      val hardwareDecodeOptionResult = PlaybackSession.setOptionString("hwdec", hardwareDecodeMode)
      val hardwareDecodeCodecsOptionResult =
        if (!MpvConfigOverridePolicy.isOwnedByMpvConf("hwdec-codecs")) {
          PlaybackSession.setOptionString("hwdec-codecs", "all")
        } else {
          null
        }
      Log.i(
        TAG,
        "MPVFLOW_OPTIONS hwdec_mode=$hardwareDecodeMode hwdec_rc=$hardwareDecodeOptionResult " +
          "hwdec_codecs_rc=${hardwareDecodeCodecsOptionResult ?: "mpv.conf"} target_fps=$mpvFlowTargetFps " +
          "max_dimension=$mpvFlowMaxDimension quality=$mpvFlowQuality search_radius=${effectiveMpvFlowSearchRadius(mpvFlowTargetFps, mpvFlowQuality)}",
      )
    } else {
      if (!MpvConfigOverridePolicy.isOwnedByMpvConf("hwdec")) {
        PlaybackSession.setOptionString("hwdec", hwdecMode)
      }
      if (!MpvConfigOverridePolicy.isOwnedByMpvConf("hwdec-codecs")) {
        PlaybackSession.setOptionString("hwdec-codecs", "all")
      }
    }

    // These were forced on between the last known-good build (e3b1de8) and the first build
    // reproducing the HEVC/Main10 frame-drop regression (84f21fc). Keep mpv's normal direct-
    // rendering heuristic and disable the extra decoder-frame queue, matching mpv's defaults.
    PlaybackSession.setOptionString("vd-lavc-dr", "auto")
    PlaybackSession.setOptionString("vd-lavc-queue", "no")

    val mpvFlowSoftBlendDebugEnabled =
      BuildConfig.DEBUG &&
        decoderPreferences.mpvFlowSoftBlendDebug.get() &&
        mpvFlowFrameInterpolationEnabled &&
        backend.gpuApi == "vulkan" &&
        !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-soft-blend") &&
        !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-compute-api")
    val effectiveMpvFlowComputeApi =
      if (mpvFlowSoftBlendDebugEnabled) MpvFlowComputeApi.VULKAN else mpvFlowComputeApi
    val mpvFlowComputeApiOptionResult =
      if (BuildConfig.MPV_HAS_MPVFLOW && !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-compute-api")) {
        PlaybackSession.setOptionString("mpvflow-compute-api", effectiveMpvFlowComputeApi.nativeValue.toString())
      } else {
        null
      }
    val captureControlOptionResult =
      if (BuildConfig.DEBUG && BuildConfig.MPV_HAS_MPVFLOW &&
        !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-diagnostic-control-file")) {
        mpvFlowCaptureControlConfigured =
          PlaybackSession.setOptionString(
            "mpvflow-diagnostic-control-file",
            if (mpvFlowFrameInterpolationEnabled) mpvFlowCaptureControlFile.absolutePath else "",
          ) == 0
        mpvFlowCaptureControlConfigured
      } else {
        false
      }
    val mpvFlowVisibilityResolverDebugEnabled =
      BuildConfig.DEBUG &&
        decoderPreferences.mpvFlowVisibilityResolverDebug.get() &&
        mpvFlowFrameInterpolationEnabled &&
        backend.gpuApi == "vulkan"
    val mpvFlowVisibilityResolverOptionResult =
      if (BuildConfig.MPV_HAS_MPVFLOW &&
        !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-visibility-resolver")) {
        PlaybackSession.setOptionString(
          "mpvflow-visibility-resolver",
          if (mpvFlowVisibilityResolverDebugEnabled) "yes" else "no",
        )
      } else {
        null
      }
    val mpvFlowSoftBlendOptionResult =
      if (BuildConfig.MPV_HAS_MPVFLOW &&
        !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow-soft-blend")) {
        PlaybackSession.setOptionString(
          "mpvflow-soft-blend",
          if (mpvFlowSoftBlendDebugEnabled) "yes" else "no",
        )
      } else {
        null
      }
    val mpvFlowRendererOptionResult =
      if (BuildConfig.MPV_HAS_MPVFLOW && !MpvConfigOverridePolicy.isOwnedByMpvConf("mpvflow")) {
        val targetFpsResult =
          if (mpvFlowFrameInterpolationEnabled) {
            PlaybackSession.setOptionString("mpvflow-target-fps", mpvFlowTargetFps.toString())
          } else {
            null
          }
        val dimensionResult =
          if (mpvFlowFrameInterpolationEnabled) {
            PlaybackSession.setOptionString("mpvflow-max-dimension", mpvFlowMaxDimension.toString())
          } else {
            null
          }
        val qualityResult =
          if (mpvFlowFrameInterpolationEnabled) {
            PlaybackSession.setOptionString("mpvflow-quality", mpvFlowQuality.toString())
          } else null
        val enabledResult =
          PlaybackSession.setOptionString("mpvflow", if (mpvFlowFrameInterpolationEnabled) "yes" else "no")
        "mpvflow_rc=$enabledResult compute_api=${effectiveMpvFlowComputeApi.name.lowercase()} " +
          "compute_api_rc=${mpvFlowComputeApiOptionResult ?: "mpv.conf_or_unavailable"} " +
          "target_fps_rc=${targetFpsResult ?: "unchanged"} " +
          "max_dimension_rc=${dimensionResult ?: "unchanged"} quality=$mpvFlowQuality quality_rc=${qualityResult ?: "unchanged"} " +
          "visibility_resolver=$mpvFlowVisibilityResolverDebugEnabled " +
          "visibility_resolver_rc=${mpvFlowVisibilityResolverOptionResult ?: "mpv.conf_or_unavailable"} " +
          "soft_blend=$mpvFlowSoftBlendDebugEnabled soft_blend_rc=${mpvFlowSoftBlendOptionResult ?: "mpv.conf_or_unavailable"}"
      } else {
        "compute_api=${effectiveMpvFlowComputeApi.name.lowercase()} " +
          "compute_api_rc=${mpvFlowComputeApiOptionResult ?: "mpv.conf_or_unavailable"} mpvflow=mpv.conf"
      }

    val appVideoFilters =
      buildList {
        if (decoderPreferences.useYUV420P.get() && !mpvFlowFrameInterpolationEnabled) {
          add("format=yuv420p")
        }
      }
    val filterOptionResult =
      if (!MpvConfigOverridePolicy.isOwnedByMpvConf("vf")) {
        if (appVideoFilters.isEmpty()) {
          PlaybackSession.setOptionString("vf-clr", "")
        } else {
          PlaybackSession.setOptionString("vf", appVideoFilters.joinToString(","))
        }
      } else {
        null
      }
    if (mpvFlowFrameInterpolationPreference) {
      val reason =
        when {
          mpvFlowFrameInterpolationEnabled -> "enabled"
          !BuildConfig.MPV_HAS_MPVFLOW -> "runtime_unavailable"
          frameInterpolationConfigOwned -> "mpv_config_owns_options"
          anime4kActive -> "anime4k_active"
          !RendererBackendPolicy.canUseDirectGpuFlow(backend.vo, backend.gpuApi) ->
            "requires_gpu_next_vulkan"
          decoderPreferences.playbackEngine.get() == PlaybackEngineMode.NATIVE -> "native_engine_selected"
          else -> "unsupported_configuration"
        }
      val interpolationHwdecDiagnostic =
        if (mpvFlowFrameInterpolationEnabled) {
          RendererBackendPolicy.gpuFlowHwdecMode(
            hardwareDecodingEnabled = decoderPreferences.tryHWDecoding.get(),
            buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
          )
        } else {
          "renderer_default"
        }
      Log.i(
        TAG,
        "MPVFLOW_DIAGNOSTIC event=config requested_enabled=$mpvFlowFrameInterpolationEnabled " +
          "runtime_state=not_verified " +
          "requested_target_fps=${decoderPreferences.mpvFlowTargetFps.get()} " +
          "effective_target_fps=$mpvFlowTargetFps display_refresh_hz=${displayRefreshRate?.hz ?: 0f} " +
          "display_refresh_source=${displayRefreshRate?.source ?: "unavailable"} " +
          "renderer=${backend.vo} gpu_api=${backend.gpuApi} " +
          "hardware_decode_mode=$interpolationHwdecDiagnostic " +
          "cpu_filter_fallback=disabled " +
          "requested_msg_level=$logLevel msg_level_apply_rc=${flowLogLevelApplyResult ?: "deferred"} " +
          "renderer_option_result=${mpvFlowRendererOptionResult ?: "skipped"} reason=$reason",
      )
    }
    if (!mpvFlowFrameInterpolationEnabled) {
      PlaybackSession.setOptionString("msg-level", logLevel)
    }

    PlaybackSession.setOptionString("keep-open", "yes")
    PlaybackSession.setOptionString("input-default-bindings", "yes")

    PlaybackSession.setOptionString("tls-verify", "yes")
    PlaybackSession.setOptionString("tls-ca-file", "${context.filesDir.path}/cacert.pem")

    val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    screenshotDir.mkdirs()
    PlaybackSession.setOptionString("screenshot-directory", screenshotDir.path)

    VideoFilters.entries.forEach {
      PlaybackSession.setOptionString(it.mpvProperty, it.preference(decoderPreferences).get().toString())
    }

    PlaybackSession.setOptionString("speed", playerPreferences.defaultSpeed.get().toString())
    // Avoid forcing CPU-side film-grain synthesis globally; this can spike thermals on mobile SoCs.
    // Let mpv choose the safest path for the active decoder/backend.
    PlaybackSession.setOptionString("vd-lavc-film-grain", "auto")

    // Streaming improvements
    // Use adaptive HLS bitrate selection to avoid forcing the heaviest stream profile.
    // This reduces thermal load and helps prevent jitter/rebuffering on long sessions.
    PlaybackSession.setOptionString("hls-bitrate", "no")
    PlaybackSession.setOptionString("http-allow-redirect", "yes")
    PlaybackSession.setOptionString("cookies", "yes")
    PlaybackSession.setOptionString("cookies-file", AndroidCookieJar.playbackCookieFile(context).absolutePath)
    PlaybackSession.setOptionString("cache", "auto")
    PlaybackSession.setOptionString("cache-pause", "yes")
    PlaybackSession.setOptionString("cache-pause-wait", "2")
    PlaybackSession.setOptionString("demuxer-max-bytes", "64MiB")
    // Recover boundedly from transient HTTP/TLS disconnects, including non-seekable live inputs.
    // Do not use reconnect_at_eof globally: a legitimate VOD EOF must still finish normally.
    PlaybackSession.setOptionString(
      "demuxer-lavf-o",
      "http_persistent=0,reconnect=1,reconnect_on_network_error=1,reconnect_streamed=1," +
        "reconnect_delay_max=5,reconnect_max_retries=5,reconnect_delay_total_max=20",
    )
    // demuxer-lavf-o only reaches demuxer-internal opens (HLS/DASH segments). The primary http(s)
    // URL is opened by stream_lavf, which reads stream-lavf-o; without it a dropped connection or
    // one failed seek-reopen permanently stalls network playback (endless buffering).
    PlaybackSession.setOptionString(
      "stream-lavf-o",
      "reconnect=1,reconnect_on_network_error=1,reconnect_on_http_error=5xx,reconnect_streamed=1," +
        "reconnect_delay_max=5,reconnect_max_retries=5,reconnect_delay_total_max=20",
    )
    // Drop only video-output-bound late frames when rendering cannot keep up.
    // This prevents long-term jitter buildup without aggressively sacrificing smoothness.
    PlaybackSession.setOptionString("framedrop", "vo")

    val preciseSeek = playerPreferences.usePreciseSeeking.get()
    PlaybackSession.setOptionString("hr-seek", if (preciseSeek) "yes" else "no")
    PlaybackSession.setOptionString("hr-seek-framedrop", if (preciseSeek) "no" else "yes")

    // GPU-next's Flow stage needs mpv's display-synchronized temporal queue so it can synthesize
    // at the presentation timestamp without a CPU filter queue. Keep the existing audio sync for
    // all other playback modes.
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("interpolation")) {
      PlaybackSession.setOptionString(
        "interpolation",
        if (mpvFlowFrameInterpolationEnabled) "yes" else "no",
      )
    }
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("video-sync")) {
      PlaybackSession.setOptionString(
        "video-sync",
        if (mpvFlowFrameInterpolationEnabled) "display-resample" else "audio",
      )
    }

    // Anime4K shader initialization (MUST be in initOptions, not after file load!)
    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) {
      applyAnime4KShaders(backend.vo, backend.gpuApi)
    }
    // HDR Toys shaders (loaded after Anime4K so they append in the correct order)
    if (!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) {
      applyHdrToysMode(hdrScreenMode, hdrPipelineReady)
    }

    setupSubtitlesOptions()
    setupAudioOptions()
    YtdlpManager.setupMpvOptions(context, ytdlPreferences, subtitlesPreferences)
  }

  override fun observeProperties() {
    for ((name, format) in observedProps) PlaybackSession.observeProperty(name, format)
  }

  override fun postInitOptions() {
    applyOsdSafeAreaMargins()

    when (decoderPreferences.debanding.get()) {
      Debanding.None -> {}
      Debanding.CPU -> PlaybackSession.command("vf", "add", "@deband:gradfun=radius=12")
      Debanding.GPU -> PlaybackSession.setOptionString("deband", "yes")
    }

    // Keep the MPV stats OSD video-only. Otherwise a music item can inherit the previous
    // video's stats overlay when MPV is reused for audio playback.
    if (PlaybackSession.queue.value.currentItem?.isDefinitelyAudioOnly() != true) {
      advancedPreferences.enabledStatisticsPage.get().let {
        if (it in 1..5) {
          PlaybackSession.command("script-binding", "stats/display-stats-toggle")
          PlaybackSession.command("script-binding", "stats/display-page-$it")
        }
      }
    }
  }

  fun applyOsdSafeAreaMargins(insets: WindowInsetsCompat? = null) {
    val resolvedInsets =
      insets ?: androidx.core.view.ViewCompat
        .getRootWindowInsets(this)
    val cutoutInsets = resolvedInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
    val horizontalMargin = maxOf(cutoutInsets?.left ?: 0, cutoutInsets?.right ?: 0).coerceAtLeast(16)
    val verticalMargin = (cutoutInsets?.top ?: 0).coerceAtLeast(16)
    PlaybackSession.setOptionString("osd-margin-x", horizontalMargin.toString())
    PlaybackSession.setOptionString("osd-margin-y", verticalMargin.toString())
  }

  @Suppress("ReturnCount", "DEPRECATION")
  fun onKey(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_MULTIPLE || KeyEvent.isModifierKey(event.keyCode)) {
      return false
    }

    var mapped = KeyMapping[event.keyCode]
    if (mapped == null) {
      // Fallback to produced glyph
      if (!event.isPrintingKey) {
        return false
      }

      val ch = event.unicodeChar
      if (ch.and(KeyCharacterMap.COMBINING_ACCENT) != 0) {
        return false // dead key
      }
      mapped = ch.toChar().toString()
    }

    if (event.repeatCount > 0) {
      return true
    }

    val mod: MutableList<String> = mutableListOf()
    event.isShiftPressed && mod.add("shift")
    event.isCtrlPressed && mod.add("ctrl")
    event.isAltPressed && mod.add("alt")
    event.isMetaPressed && mod.add("meta")

    val action = if (event.action == KeyEvent.ACTION_DOWN) "keydown" else "keyup"
    mod.add(mapped)
    PlaybackSession.command(action, mod.joinToString("+"))

    return true
  }

  override fun surfaceChanged(
    holder: android.view.SurfaceHolder,
    format: Int,
    width: Int,
    height: Int,
  ) {
    PlaybackSession.resizeSurface(width, height, owner = this)
    updateSurfaceFrameRateHint()
  }

  override fun surfaceCreated(holder: android.view.SurfaceHolder) {
    isSurfaceReady = PlaybackSession.bindSurface(holder.surface, width, height, this)
    updateSurfaceFrameRateHint()
    updateMpvFlowDiagnostics()
    post {
      if (isSurfaceReady && holder.surface.isValid) {
        onSurfaceReady?.invoke()
      }
    }
  }

  override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
    stopMpvFlowDiagnostics()
    isSurfaceReady = false
    PlaybackSession.unbindSurface(this)
  }

  override fun onDetachedFromWindow() {
    stopMpvFlowDiagnostics()
    super.onDetachedFromWindow()
  }

  internal fun updateSurfaceFrameRateHint() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      val coreIsInitialized = PlaybackSession.isInitialized
      val activeInterpolationFrameRate =
        if (coreIsInitialized) {
          PlaybackSession.surfaceFrameRateHintHz()
        } else {
          interpolationOutputFrameRate
        }
      val hintSource = if (coreIsInitialized) "playback_session" else "view_initialization"
      val fps =
        resolveSurfaceFrameRateHint(
          sourceFrameRate = PlaybackSession.getPropertyDouble("container-fps"),
          interpolationFrameRate = activeInterpolationFrameRate,
        )
      if (fps != null && holder?.surface?.isValid == true) {
        try {
          holder.surface.setFrameRate(
            fps.toFloat(),
            android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
          )
          Log.i(
            TAG,
            "MPVFLOW_DIAGNOSTIC event=surface_frame_rate_hint rate_hz=$fps " +
              "interpolation_target_hz=${activeInterpolationFrameRate ?: "none"} " +
              "source=$hintSource",
          )
        } catch (e: Exception) {
          Log.e(TAG, "Failed to set frame rate on surface", e)
        }
      }
    }
  }

  private val observedProps =
    mapOf(
      "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "cache-buffering-state" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "demuxer-cache-duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "network" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "video-params/aspect" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "video-params/w" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "video-params/h" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
      "container-fps" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
      "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "user-data/Mpv∞/show_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/toggle_ui" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/show_panel" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/set_button_title" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/reset_button_title" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/toggle_button" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/seek_by" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/seek_to" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/seek_by_with_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/seek_to_with_text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "user-data/Mpv∞/software_keyboard" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // Curl bridge: scripts write a JSON request here; response is written to curl_response
      "user-data/Mpv∞/curl_request" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // curl_response is written by the bridge; scripts observe this property for results
      "user-data/Mpv∞/curl_response" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      // Track console visibility state
      "user-data/mpv/console/open" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
      "sub-text" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
      "sub-scale" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
    )

  private fun setupAudioOptions() {
    // Disable MPV's automatic audio selection
    // App will handle track selection manually via TrackSelector to respect user choices
    PlaybackSession.setOptionString("alang", "")
    PlaybackSession.setOptionString("audio-display", "embedded-first")
    PlaybackSession.setOptionString("audio-delay", (audioPreferences.defaultAudioDelay.get() / 1000.0).toString())
    PlaybackSession.setOptionString("audio-pitch-correction", audioPreferences.audioPitchCorrection.get().toString())
    PlaybackSession.setOptionString("volume-max", (audioPreferences.volumeBoostCap.get() + 100).toString())
    // Prevent automatic volume normalization when downmixing multi-channel audio
    PlaybackSession.setOptionString("audio-normalize-downmix", "no")
  }

  // Setup
  private fun setupSubtitlesOptions() {
    // Disable MPV's automatic subtitle selection
    // App will handle track selection manually via TrackSelector to respect user choices
    PlaybackSession.setOptionString("slang", "")
    PlaybackSession.setOptionString("sub-auto", "no")
    PlaybackSession.setOptionString("sub-file-paths", "")
    PlaybackSession.setOptionString("subs-fallback", "no")

    val fontsDirPath = "${context.filesDir.path}/fonts/"
    PlaybackSession.setOptionString("sub-fonts-dir", fontsDirPath)
    // Auto-detect subtitle encoding
    PlaybackSession.setOptionString("sub-codepage", "auto")
    // Allow embedded fonts from MKV/MP4 containers
    PlaybackSession.setOptionString("embeddedfonts", "yes")
    // Auto-detect font provider (system fonts, embedded fonts, etc.)
    PlaybackSession.setOptionString("sub-font-provider", "auto")

    // Delay and speed for both primary and secondary
    val subDelay = (subtitlesPreferences.defaultSubDelay.get() / 1000.0).toString()
    val subSpeed = subtitlesPreferences.defaultSubSpeed.get().toString()
    PlaybackSession.setOptionString("sub-delay", subDelay)
    PlaybackSession.setOptionString("sub-speed", subSpeed)
    PlaybackSession.setOptionString("secondary-sub-delay", subDelay)
    PlaybackSession.setOptionString("secondary-sub-speed", subSpeed)

    val preferredFont = subtitlesPreferences.font.get().ifBlank { DEFAULT_SUBTITLE_FONT_FAMILY }
    PlaybackSession.setOptionString("sub-font", preferredFont)
    PlaybackSession.setOptionString("secondary-sub-font", preferredFont)

    if (subtitlesPreferences.overrideAssSubs.get()) {
      PlaybackSession.setOptionString("sub-ass-override", "force")
      PlaybackSession.setOptionString("sub-ass-justify", "yes")
      PlaybackSession.setOptionString("secondary-sub-ass-override", "force")
    } else {
      PlaybackSession.setOptionString("sub-ass-override", "no")
      PlaybackSession.setOptionString("secondary-sub-ass-override", "no")
    }

    // Typography and styling for both primary and secondary MPV subtitle tracks.
    val fontSize = subtitlesPreferences.fontSize.get().toString()
    val bold = if (subtitlesPreferences.bold.get()) "yes" else "no"
    val italic = if (subtitlesPreferences.italic.get()) "yes" else "no"
    val justify = subtitlesPreferences.justification.get().value
    val textColor = subtitlesPreferences.textColor.get().toColorHexString()
    val backgroundColor = subtitlesPreferences.backgroundColor.get().toColorHexString()
    val borderColor = subtitlesPreferences.borderColor.get().toColorHexString()
    val shadowColor = subtitlesPreferences.shadowColor.get().toColorHexString()
    val borderSize = subtitlesPreferences.borderSize.get().toString()
    val borderStyle = subtitlesPreferences.borderStyle.get().value
    val shadowOffset = subtitlesPreferences.shadowOffset.get().toString()
    val subPos = clampSubtitlePosition(subtitlesPreferences.subPos.get())
    val w = width.takeIf { it > 0 }?.toFloat() ?: context.resources.displayMetrics.widthPixels.toFloat()
    val h = height.takeIf { it > 0 }?.toFloat() ?: context.resources.displayMetrics.heightPixels.toFloat()
    val secondarySubPos = calculateSecondarySubtitlePosition(subPos, w, h)
    val subScale = subtitlesPreferences.subScale.get().toString()

    val scaleByWindow = if (subtitlesPreferences.scaleByWindow.get()) "yes" else "no"
    val blendMode =
      if (subtitlesPreferences.blendSubtitlesWithVideo.get() &&
        playerPreferences.isAmbientEnabled.get()
      ) {
        "video"
      } else {
        "no"
      }
    PlaybackSession.setOptionString("blend-subtitles", blendMode)

    for ((prefix, pos) in listOf("sub-" to subPos.toString(), "secondary-sub-" to secondarySubPos.toString())) {
      PlaybackSession.setOptionString("${prefix}font-size", fontSize)
      PlaybackSession.setOptionString("${prefix}bold", bold)
      PlaybackSession.setOptionString("${prefix}italic", italic)
      PlaybackSession.setOptionString("${prefix}justify", justify)
      PlaybackSession.setOptionString("${prefix}color", textColor)
      PlaybackSession.setOptionString("${prefix}back-color", backgroundColor)
      PlaybackSession.setOptionString("${prefix}border-color", borderColor)
      PlaybackSession.setOptionString("${prefix}shadow-color", shadowColor)
      PlaybackSession.setOptionString("${prefix}border-size", borderSize)
      PlaybackSession.setOptionString("${prefix}border-style", borderStyle)
      PlaybackSession.setOptionString("${prefix}shadow-offset", shadowOffset)
      PlaybackSession.setOptionString("${prefix}scale", subScale)
      PlaybackSession.setOptionString("${prefix}pos", pos)
      PlaybackSession.setOptionString("${prefix}scale-by-window", scaleByWindow)
      PlaybackSession.setOptionString("${prefix}use-margins", scaleByWindow)
    }
  }

  fun applyAnime4KShaders() {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.ANIME4K)) return
    applyAnime4KShaders(
      activeVo = PlaybackSession.getPropertyString("vo") ?: "",
      activeGpuApi = PlaybackSession.getPropertyString("gpu-api") ?: "",
    )
  }

  /**
   * Copies bundled hdr-toys GLSL shaders to filesDir on first use, then appends
   * the chosen profile's shader chain to mpv's glsl-shaders list.
   * Safe to call on every init — clears previous hdr-toys shaders before re-applying.
   */
  fun applyHdrToysMode(
    mode: HdrScreenMode,
    pipelineReady: Boolean,
  ) {
    if (MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)) return
    val profile = mode.hdrToysProfile
    if (!pipelineReady || profile == null) {
      hdrToysManager.clear()
      return
    }
    if (!hdrToysManager.apply(profile)) {
      Log.w(TAG, "Skipping HDR Toys mode — bundled shaders unavailable: ${mode.name}")
    }
  }

  private fun applyAnime4KShaders(
    activeVo: String,
    activeGpuApi: String,
  ) {
    runCatching {
      val isGpuNext = activeVo == "gpu-next"
      val useVulkan = activeGpuApi == "vulkan"

      // ── Standard Anime4K (requires master switch) ─────────────────────────
      val enabled = decoderPreferences.enableAnime4K.get()
      if (!enabled) {
        clearAnime4KShaders()
        return
      }

      // Standard mode needs legacy gpu OR gpu-next+Vulkan
      if (isGpuNext && !useVulkan) {
        Log.w(TAG, "Skipping standard Anime4K — gpu-next without Vulkan")
        return
      }

      val modeStr = decoderPreferences.anime4kMode.get()
      if (modeStr == "OFF") {
        clearAnime4KShaders()
        return
      }

      // Parse user's selected mode
      val mode =
        try {
          Anime4KManager.Mode.valueOf(modeStr)
        } catch (e: IllegalArgumentException) {
          Anime4KManager.Mode.OFF
        }

      val selection =
        selectRuntimeStableAnime4K(
          mode = mode,
          quality = decoderPreferences.anime4kQuality.get(),
          context = context,
          enableIn4k = decoderPreferences.anime4kIn4k.get(),
        )
      selection.reason?.let { reason ->
        Log.i(TAG, "Anime4K thermal guard: $reason")
      }
      if (selection.mode == Anime4KManager.Mode.OFF) {
        clearAnime4KShaders()
        return
      }

      anime4kManager.setPostFilters(
        darken = decoderPreferences.anime4kDarken.get(),
        thin = decoderPreferences.anime4kThin.get(),
        deblur = decoderPreferences.anime4kDeblur.get(),
      )
      if (applyAnime4KShaderChain(anime4kManager, selection.mode, selection.quality)) {
        applyAnime4KStabilityOptions(useVulkan = useVulkan)
      } else {
        Log.w(
          TAG,
          "Anime4K shader chain is empty for mode=${selection.mode} quality=${selection.quality}",
        )
      }
    }.onFailure {
      Log.w(TAG, "Failed to apply Anime4K shaders", it)
    }
  }

  private fun shouldUseVulkan(
    ignoreForcedOpenGlFallback: Boolean = false,
    forceForMpvFlow: Boolean = false,
  ): Boolean {
    val canUseVulkan =
      RendererBackendPolicy.canUseVulkan(
        buildIncludesVulkan = BuildConfig.MPV_SUPPORTS_VULKAN,
        deviceSupportsVulkan = VulkanCapabilities.isDeviceSupported(context),
        userEnabledVulkan = decoderPreferences.useVulkan.get() || forceForMpvFlow,
        forceOpenGlFallback = forceOpenGlFallback && !ignoreForcedOpenGlFallback,
      )
    if ((decoderPreferences.useVulkan.get() || forceForMpvFlow) && !canUseVulkan) {
      Log.w(TAG, "Vulkan is unavailable for this build or device. Forcing OpenGL.")
    }
    return canUseVulkan
  }

  private fun preferredHwdecMode(usesVulkan: Boolean): String =
    RendererBackendPolicy.preferredHwdecMode(
      hardwareDecodingEnabled = decoderPreferences.tryHWDecoding.get(),
      usesVulkan = usesVulkan,
      buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
    )

  private fun selectRenderBackend(ignoreForcedOpenGlFallback: Boolean = false): RenderBackendSelection {
    val anime4kEnabled =
      decoderPreferences.enableAnime4K.get() &&
        (decoderPreferences.anime4kMode.get() != "OFF")
    val flowRequiresVulkan =
      decoderPreferences.mpvFlowFrameInterpolation.get() &&
        BuildConfig.MPV_HAS_MPVFLOW &&
        BuildConfig.MPV_SUPPORTS_VULKAN &&
        !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.FRAME_INTERPOLATION) &&
        !anime4kEnabled &&
        decoderPreferences.playbackEngine.get() != PlaybackEngineMode.NATIVE
    val gpuNextEnabled = decoderPreferences.gpuNext.get() || flowRequiresVulkan
    val vulkanEnabled = shouldUseVulkan(
      ignoreForcedOpenGlFallback = ignoreForcedOpenGlFallback,
      forceForMpvFlow = flowRequiresVulkan,
    )

    if (anime4kEnabled && gpuNextEnabled && !vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu",
        gpuApi = "opengl",
        gpuContext = "android",
        reason = "Anime4K with gpu-next but without Vulkan is unsupported: fallback to legacy gpu/opengl",
      )
    }

    if (gpuNextEnabled && vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu-next",
        gpuApi = "vulkan",
        gpuContext = "androidvk",
        reason =
          if (anime4kEnabled) {
            "Anime4K active with gpu-next and Vulkan enabled: keep gpu-next/vulkan path"
          } else {
            "gpu-next and Vulkan enabled: use gpu-next/vulkan"
          },
      )
    }

    if (gpuNextEnabled) {
      return RenderBackendSelection(
        vo = "gpu-next",
        gpuApi = "opengl",
        gpuContext = "android",
        reason = "gpu-next enabled without Vulkan: use gpu-next/opengl",
      )
    }

    if (vulkanEnabled) {
      return RenderBackendSelection(
        vo = "gpu",
        gpuApi = "vulkan",
        gpuContext = "androidvk",
        reason =
          if (anime4kEnabled) {
            "Anime4K active with legacy gpu and Vulkan enabled: use gpu/vulkan"
          } else {
            "Vulkan enabled with legacy gpu selected: use gpu/vulkan"
          },
      )
    }

    return RenderBackendSelection(
      vo = "gpu",
      gpuApi = "opengl",
      gpuContext = "android",
      reason =
        if (anime4kEnabled) {
          "Anime4K active with legacy gpu selected: use gpu/opengl"
        } else {
          "gpu-next and Vulkan disabled: use gpu/opengl"
        },
    )
  }
}
