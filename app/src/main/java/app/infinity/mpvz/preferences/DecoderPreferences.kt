/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.preferences

import app.infinity.mpvz.domain.anime4k.Anime4KManager
import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.preferences.preference.getEnum
import app.infinity.mpvz.ui.player.Debanding
import app.infinity.mpvz.ui.player.HdrScreenMode
import app.infinity.mpvz.ui.player.PlaybackEngineMode

internal val MPVFLOW_TARGET_FPS_OPTIONS = listOf(48, 60, 72, 90, 96, 120, 144)
internal const val DEFAULT_MPVFLOW_TARGET_FPS = 120
internal const val DEFAULT_MPVFLOW_QUALITY = 60

internal fun normalizeMpvFlowQuality(value: Int): Int = value.coerceIn(0, 100)

enum class MpvFlowComputeApi(val nativeValue: Int) {
  AUTO(0),
  OPENCL(1),
  VULKAN(2),
}

internal fun normalizeMpvFlowTargetFps(value: Int): Int =
  MPVFLOW_TARGET_FPS_OPTIONS.minByOrNull { kotlin.math.abs(it.toLong() - value.toLong()) }
    ?: DEFAULT_MPVFLOW_TARGET_FPS

internal fun effectiveMpvFlowTargetFps(value: Int, displayRefreshHz: Float): Int {
  val requested = normalizeMpvFlowTargetFps(value)
  if (!displayRefreshHz.isFinite() || displayRefreshHz <= 0f) return requested
  return MPVFLOW_TARGET_FPS_OPTIONS
    .filter { it <= displayRefreshHz + 0.5f && it <= requested }
    .maxOrNull()
    ?: kotlin.math.floor(displayRefreshHz.toDouble()).toInt().coerceAtLeast(1)
}

internal fun effectiveMpvFlowMaxDimension(targetFps: Int): Int =
  effectiveMpvFlowMaxDimension(targetFps, DEFAULT_MPVFLOW_QUALITY)

internal fun effectiveMpvFlowMaxDimension(targetFps: Int, quality: Int): Int {
  val q = normalizeMpvFlowQuality(quality)
  val (minimum, maximum) =
    when {
      targetFps >= 144 -> 288 to 320
      targetFps >= 120 -> 384 to 448
      targetFps >= 96 -> 320 to 432
      targetFps >= 72 -> 384 to 480
      else -> 480 to 640
    }
  // Lower profiles reduce motion-analysis work; higher profiles spend more of the frame budget
  // on a denser field. Keep dimensions aligned for GPU tiles and apply this mapping only once.
  val scaledDimension = minimum + (maximum - minimum) * q / 100
  return (scaledDimension / 16) * 16
}

internal fun effectiveMpvFlowSearchRadius(targetFps: Int, quality: Int): Int {
  val q = normalizeMpvFlowQuality(quality)
  return when {
    targetFps >= 144 -> if (q >= 75) 4 else 3
    targetFps >= 120 -> if (q >= 75) 5 else 4
    targetFps >= 96 -> if (q >= 60) 6 else 5
    else -> if (q >= 60) 8 else 6
  }
}

class DecoderPreferences(
  preferenceStore: PreferenceStore,
) {
  val playbackEngine = preferenceStore.getEnum("playback_engine", PlaybackEngineMode.AUTO)
  val profile = preferenceStore.getString("mpv_profile", "fast")
  val tryHWDecoding = preferenceStore.getBoolean("try_hw_dec", true)
  val gpuNext = preferenceStore.getBoolean("gpu_next")
  val useVulkan = preferenceStore.getBoolean("use_vulkan", false)
  val hdrScreenOutput = preferenceStore.getBoolean("hdr_screen_output", false)
  val hdrScreenMode = preferenceStore.getEnum("hdr_screen_mode", HdrScreenMode.OFF)
  val lastHdrMode = preferenceStore.getEnum("hdr_last_selected_mode", HdrScreenMode.BT_2020)

  /** Boost SDR content into the HDR range when using the Linear HDR pipeline. */
  val boostSdrToHdr = preferenceStore.getBoolean("boost_sdr_to_hdr", true)
  val useYUV420P = preferenceStore.getBoolean("use_yuv420p", false)
  val mpvFlowFrameInterpolation = preferenceStore.getBoolean("mpvflow_frame_interpolation", false)
  val mpvFlowTargetFps = preferenceStore.getInt("mpvflow_target_fps", DEFAULT_MPVFLOW_TARGET_FPS)
  val mpvFlowQuality = preferenceStore.getInt("mpvflow_quality", DEFAULT_MPVFLOW_QUALITY)
  val mpvFlowComputeApi = preferenceStore.getEnum("mpvflow_compute_api", MpvFlowComputeApi.AUTO)
  val mpvFlowVisibilityResolverDebug = preferenceStore.getBoolean("mpvflow_visibility_resolver_debug", false)
  val mpvFlowSoftBlendDebug = preferenceStore.getBoolean("mpvflow_soft_blend_debug", false)

  val debanding = preferenceStore.getEnum("debanding", Debanding.None)
  val debandIterations = preferenceStore.getInt("deband_iterations", 1)
  val debandThreshold = preferenceStore.getInt("deband_threshold", 48)
  val debandRange = preferenceStore.getInt("deband_range", 16)
  val debandGrain = preferenceStore.getInt("deband_grain", 32)

  val brightnessFilter = preferenceStore.getInt("filter_brightness")
  val saturationFilter = preferenceStore.getInt("filter_saturation")
  val gammaFilter = preferenceStore.getInt("filter_gamma")
  val contrastFilter = preferenceStore.getInt("filter_contrast")
  val hueFilter = preferenceStore.getInt("filter_hue")
  val sharpnessFilter = preferenceStore.getInt("filter_sharpness")

  // Anime4K Preferences
  val enableAnime4K = preferenceStore.getBoolean("enable_anime4k", false)
  val anime4kMode = preferenceStore.getString("anime4k_mode", "OFF")
  val anime4kQuality = preferenceStore.getEnum("anime4k_quality", Anime4KManager.DEFAULT_QUALITY)
  val anime4kIn4k = preferenceStore.getBoolean("anime4k_in_4k", false)
  val anime4kDarken = preferenceStore.getBoolean("anime4k_darken", false)
  val anime4kThin = preferenceStore.getBoolean("anime4k_thin", false)
  val anime4kDeblur = preferenceStore.getBoolean("anime4k_deblur", false)
}
