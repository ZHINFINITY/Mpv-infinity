/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.preferences

import app.infinity.mpvz.domain.anime4k.Anime4KManager
import app.infinity.mpvz.preferences.preference.PreferenceStore
import app.infinity.mpvz.preferences.preference.getEnum
import app.infinity.mpvz.ui.player.Debanding
import app.infinity.mpvz.ui.player.HdrScreenMode
import app.infinity.mpvz.ui.player.PlaybackEngineMode

internal val RIFE_TARGET_FPS_OPTIONS = listOf(30, 48, 60)
internal const val DEFAULT_RIFE_TARGET_FPS = 30

internal fun normalizeRifeTargetFps(value: Int): Int =
  RIFE_TARGET_FPS_OPTIONS.minByOrNull { kotlin.math.abs(it.toLong() - value.toLong()) }
    ?: DEFAULT_RIFE_TARGET_FPS

internal val MPVFLOW_TARGET_FPS_OPTIONS = listOf(48, 60, 72, 90, 96, 120, 144)
internal const val DEFAULT_MPVFLOW_TARGET_FPS = 60

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

internal val RIFE_PROCESSING_RESOLUTION_OPTIONS = listOf(-1, 0, 480, 720, 1080)
internal const val DEFAULT_RIFE_PROCESSING_RESOLUTION = 0

internal fun normalizeRifeProcessingResolution(value: Int): Int =
  value.takeIf { it in RIFE_PROCESSING_RESOLUTION_OPTIONS }
    ?: RIFE_PROCESSING_RESOLUTION_OPTIONS.filter { it > 0 }.minByOrNull {
      kotlin.math.abs(it.toLong() - value.toLong())
    }
    ?: DEFAULT_RIFE_PROCESSING_RESOLUTION

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
  val rifeFrameInterpolation = preferenceStore.getBoolean("rife_frame_interpolation", false)
  val rifeTargetFps = preferenceStore.getInt("rife_target_fps", DEFAULT_RIFE_TARGET_FPS)
  val rifeProcessingResolution =
    preferenceStore.getInt("rife_processing_resolution", DEFAULT_RIFE_PROCESSING_RESOLUTION)
  val mpvFlowFrameInterpolation = preferenceStore.getBoolean("mpvflow_frame_interpolation", false)
  val mpvFlowTargetFps = preferenceStore.getInt("mpvflow_target_fps", DEFAULT_MPVFLOW_TARGET_FPS)

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
