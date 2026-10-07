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
  fun isEligible(
    rifeEnabled: Boolean,
    hardwareDecodingEnabled: Boolean,
    modelAvailable: Boolean,
    videoOutput: String,
    gpuApi: String,
    timingOptionsOwnedByUser: Boolean,
  ): Boolean =
    rifeEnabled &&
      hardwareDecodingEnabled &&
      modelAvailable &&
      videoOutput == "gpu-next" &&
      gpuApi == "opengl" &&
      !timingOptionsOwnedByUser
}
