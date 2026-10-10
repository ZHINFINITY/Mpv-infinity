/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
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
}
