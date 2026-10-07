/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererBackendPolicyTest {
  @Test
  fun directGpuFlowRequiresGpuNextWithOpenGl() {
    assertEquals(true, RendererBackendPolicy.canUseDirectGpuFlow("gpu-next", "opengl"))
    assertEquals(false, RendererBackendPolicy.canUseDirectGpuFlow("gpu-next", "vulkan"))
    assertEquals(false, RendererBackendPolicy.canUseDirectGpuFlow("gpu", "opengl"))
  }

  @Test
  fun gpuFlowDoesNotRequestMediaCodecCopyMode() {
    assertEquals("mediacodec,no", RendererBackendPolicy.gpuFlowHwdecMode(true))
    assertEquals("no", RendererBackendPolicy.gpuFlowHwdecMode(false))
  }
}
