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
}
