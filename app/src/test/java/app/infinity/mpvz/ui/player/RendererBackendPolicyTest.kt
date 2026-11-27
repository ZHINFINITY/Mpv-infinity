/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererBackendPolicyTest {
  @Test
  fun directGpuFlowRequiresGpuNextWithVulkan() {
    assertEquals(true, RendererBackendPolicy.canUseDirectGpuFlow("gpu-next", "vulkan"))
    assertEquals(false, RendererBackendPolicy.canUseDirectGpuFlow("gpu-next", "opengl"))
    assertEquals(false, RendererBackendPolicy.canUseDirectGpuFlow("gpu", "opengl"))
  }

  @Test
  fun gpuFlowUsesDirectMediaCodecOnlyWhenTheBuildSupportsVulkanImport() {
    assertEquals(
      "mediacodec,mediacodec-copy,no",
      RendererBackendPolicy.gpuFlowHwdecMode(true, buildSupportsMediaCodecVulkan = true),
    )
    assertEquals(
      "mediacodec-copy,no",
      RendererBackendPolicy.gpuFlowHwdecMode(true, buildSupportsMediaCodecVulkan = false),
    )
    assertEquals("no", RendererBackendPolicy.gpuFlowHwdecMode(false, buildSupportsMediaCodecVulkan = false))
  }

  @Test
  fun openGlPathCopiesFramesBecauseDirectSurfacesCannotBeRepainted() {
    // A seek on the OpenGL path leaves a flushed hardware decoder painting a stale blue surface.
    assertEquals(
      "mediacodec-copy,no",
      RendererBackendPolicy.preferredHwdecMode(
        hardwareDecodingEnabled = true,
        usesVulkan = false,
        buildSupportsMediaCodecVulkan = true,
      ),
    )
    assertEquals(
      "mediacodec-copy,no",
      RendererBackendPolicy.preferredHwdecMode(
        hardwareDecodingEnabled = true,
        usesVulkan = true,
        buildSupportsMediaCodecVulkan = false,
      ),
    )
    assertEquals(
      "mediacodec,mediacodec-copy,no",
      RendererBackendPolicy.preferredHwdecMode(
        hardwareDecodingEnabled = true,
        usesVulkan = true,
        buildSupportsMediaCodecVulkan = true,
      ),
    )
  }

  @Test
  fun explicitDecoderPickOverridesTheSafeDefault() {
    // "HW+" is what the user picked; it must survive a new file instead of snapping back to the
    // copy-mode default that showed up as "HW".
    assertEquals(
      "mediacodec,mediacodec-copy,no",
      RendererBackendPolicy.hwdecForRequestedDecoder(true, "mediacodec"),
    )
    assertEquals(
      "mediacodec-copy,no",
      RendererBackendPolicy.hwdecForRequestedDecoder(true, "mediacodec-copy"),
    )
    assertEquals("no", RendererBackendPolicy.hwdecForRequestedDecoder(true, "no"))
    // Auto picks keep the renderer policy, and hardware off always wins.
    assertEquals(null, RendererBackendPolicy.hwdecForRequestedDecoder(true, "auto"))
    assertEquals(null, RendererBackendPolicy.hwdecForRequestedDecoder(true, "auto-copy"))
    assertEquals(null, RendererBackendPolicy.hwdecForRequestedDecoder(true, ""))
    assertEquals("no", RendererBackendPolicy.hwdecForRequestedDecoder(false, "mediacodec"))
  }
}
