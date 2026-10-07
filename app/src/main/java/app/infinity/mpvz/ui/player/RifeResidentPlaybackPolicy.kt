/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

/**
 * Selection policy for the experimental GPU-resident RIFE path.
 *
 * RIFE inference remains Vulkan/NCNN. The `opengl` requirement only selects
 * the existing GLES VO as an AHardwareBuffer presentation consumer; it does
 * not route inference or interpolation through OpenGL.
 */
internal object RifeResidentPlaybackPolicy {
  data class OptionResults(
    val resident: Int,
    val modelDir: Int,
    val targetFps: Int,
    val maxDimension: Int,
    val displayFpsOverride: Int? = null,
    val residentDisable: Int = 0,
    val residentEnable: Int? = null,
  ) {
    val allAccepted: Boolean
      get() =
        resident == 0 && modelDir == 0 && targetFps == 0 && maxDimension == 0 &&
          displayFpsOverride == 0
  }

  fun shouldUseResidentRoute(candidate: Boolean, results: OptionResults?): Boolean =
    candidate && results?.allAccepted == true

  fun activeFilterPath(
    residentSelected: Boolean,
    cpuFilterRequested: Boolean,
    cpuFilterSetResult: Int?,
  ): String =
    when {
      residentSelected -> "resident_vulkan_ncnn"
      cpuFilterRequested && cpuFilterSetResult == 0 -> "cpu_rgb24_filter"
      else -> "none"
    }

  fun isEligible(
    rifeEnabled: Boolean,
    hardwareDecodingEnabled: Boolean,
    modelAvailable: Boolean,
    videoOutput: String,
    gpuApi: String,
    displayRefreshRateAvailable: Boolean,
    timingOptionsOwnedByUser: Boolean,
  ): Boolean =
    rifeEnabled &&
      hardwareDecodingEnabled &&
      modelAvailable &&
      videoOutput == "gpu-next" &&
      gpuApi == "opengl" &&
      displayRefreshRateAvailable &&
      !timingOptionsOwnedByUser
}
