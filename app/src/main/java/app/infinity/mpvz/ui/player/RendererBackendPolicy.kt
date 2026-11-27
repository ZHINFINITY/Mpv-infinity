/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

internal object RendererBackendPolicy {
  fun canUseVulkan(
    buildIncludesVulkan: Boolean,
    deviceSupportsVulkan: Boolean,
    userEnabledVulkan: Boolean,
    forceOpenGlFallback: Boolean,
  ): Boolean =
    buildIncludesVulkan &&
      deviceSupportsVulkan &&
      userEnabledVulkan &&
      !forceOpenGlFallback

  /**
   * Zero-copy MediaCodec needs somewhere to hand the decoder surface: only the Vulkan import
   * path has one. On the OpenGL output mpv cannot take `mediacodec` frames, so every load ends in
   * `vf:fatal Cannot convert decoder/filter output` and mpv silently restarts in copy mode — the
   * "starts as HW" the user saw, plus a wasted decoder init on each file.
   */
  fun canUseDirectMediaCodec(
    usesVulkan: Boolean,
    buildSupportsMediaCodecVulkan: Boolean,
  ): Boolean = usesVulkan && buildSupportsMediaCodecVulkan

  fun canUseDirectGpuFlow(
    renderer: String,
    gpuApi: String,
  ): Boolean = renderer == "gpu-next" && gpuApi == "vulkan"

  fun gpuFlowHwdecMode(
    hardwareDecodingEnabled: Boolean,
    buildSupportsMediaCodecVulkan: Boolean,
  ): String = preferredHwdecMode(
    hardwareDecodingEnabled = hardwareDecodingEnabled,
    usesVulkan = true,
    buildSupportsMediaCodecVulkan = buildSupportsMediaCodecVulkan,
  )

  /**
   * An explicit pick from the decoder sheet wins over the safe default, but only as far as the
   * active renderer can honour it: HW+ (direct import) is clamped to the copy path when the
   * output cannot take decoder frames, so the player never burns a failed direct attempt per
   * file. Auto picks return null so [preferredHwdecMode] still picks the safe mode.
   */
  fun hwdecForRequestedDecoder(
    hardwareDecodingEnabled: Boolean,
    requested: String,
    directMediaCodecAvailable: Boolean,
  ): String? {
    if (!hardwareDecodingEnabled) return "no"
    return when (val value = requested.trim()) {
      "", "auto", "auto-copy" -> null
      "mediacodec" ->
        if (directMediaCodecAvailable) {
          "mediacodec,mediacodec-copy,no"
        } else {
          "mediacodec-copy,no"
        }
      "mediacodec-copy" -> "mediacodec-copy,no"
      else -> value
    }
  }

  fun preferredHwdecMode(
    hardwareDecodingEnabled: Boolean,
    usesVulkan: Boolean,
    buildSupportsMediaCodecVulkan: Boolean,
  ): String {
    if (!hardwareDecodingEnabled) return "no"

    // Only the Vulkan-import path can hand the decoder's surface through mpv's renderer. The
    // OpenGL path decodes straight into the screen surface, which mpv cannot repaint after a
    // seek, so Qualcomm HEVC shows a blue/garbage frame until the decoder restarts. Copy mode
    // keeps frames inside the renderer and makes seeks safe.
    return if (canUseDirectMediaCodec(usesVulkan, buildSupportsMediaCodecVulkan) && usesVulkan) {
      "mediacodec,mediacodec-copy,no"
    } else {
      "mediacodec-copy,no"
    }
  }

}
