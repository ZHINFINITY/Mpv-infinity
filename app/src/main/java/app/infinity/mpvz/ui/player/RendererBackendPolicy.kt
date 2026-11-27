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

  fun canUseDirectMediaCodec(
    usesVulkan: Boolean,
    buildSupportsMediaCodecVulkan: Boolean,
  ): Boolean = !usesVulkan || buildSupportsMediaCodecVulkan

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
