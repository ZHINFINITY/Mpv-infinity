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
  fun directMediaCodecRequiresTheVulkanImportPath() {
    // On the OpenGL output mpv cannot take MediaCodec frames: it logs "cannot copy surface of
    // this format to CPU memory" and restarts the decoder in copy mode on every load.
    assertEquals(true, RendererBackendPolicy.canUseDirectMediaCodec(true, true))
    assertEquals(false, RendererBackendPolicy.canUseDirectMediaCodec(true, false))
    assertEquals(false, RendererBackendPolicy.canUseDirectMediaCodec(false, true))
    assertEquals(false, RendererBackendPolicy.canUseDirectMediaCodec(false, false))
  }

  @Test
  fun explicitDecoderPickIsClampedToWhatTheRendererCanDo() {
    // "HW+" survives a new file when the renderer can import decoder frames ...
    assertEquals(
      "mediacodec,mediacodec-copy,no",
      RendererBackendPolicy.hwdecForRequestedDecoder(
        hardwareDecodingEnabled = true,
        requested = "mediacodec",
        directMediaCodecAvailable = true,
      ),
    )
    // ... and asks for the copy path directly when it cannot, instead of failing first per file.
    assertEquals(
      "mediacodec-copy,no",
      RendererBackendPolicy.hwdecForRequestedDecoder(
        hardwareDecodingEnabled = true,
        requested = "mediacodec",
        directMediaCodecAvailable = false,
      ),
    )
    assertEquals(
      "mediacodec-copy,no",
      RendererBackendPolicy.hwdecForRequestedDecoder(true, "mediacodec-copy", true),
    )
    assertEquals("no", RendererBackendPolicy.hwdecForRequestedDecoder(true, "no", true))
    // Auto picks keep the renderer policy, and hardware off always wins.
    assertEquals(null, RendererBackendPolicy.hwdecForRequestedDecoder(true, "auto", true))
    assertEquals(null, RendererBackendPolicy.hwdecForRequestedDecoder(true, "auto-copy", true))
    assertEquals(null, RendererBackendPolicy.hwdecForRequestedDecoder(true, "", true))
    assertEquals(
      "no",
      RendererBackendPolicy.hwdecForRequestedDecoder(false, "mediacodec", true),
    )
  }
}
