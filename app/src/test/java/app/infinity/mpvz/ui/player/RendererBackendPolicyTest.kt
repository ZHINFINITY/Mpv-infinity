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
  fun interpolationHwdecListsMapToHardwarePlusAndCopy() {
    assertEquals(Decoder.HWPlus, Decoder.getDecoderFromValue("mediacodec,mediacodec-copy,no"))
    assertEquals(Decoder.HWPlus, Decoder.getDecoderFromValue("mediacodec"))
    assertEquals(Decoder.HW, Decoder.getDecoderFromValue("mediacodec-copy,no"))
    assertEquals(Decoder.HW, Decoder.getDecoderFromValue("mediacodec-copy"))
    assertEquals(Decoder.Auto, Decoder.getDecoderFromValue("auto"))
  }
}
