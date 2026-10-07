/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RifeResidentPlaybackPolicyTest {
  private fun eligible(
    rifeEnabled: Boolean = true,
    hardwareDecodingEnabled: Boolean = true,
    modelAvailable: Boolean = true,
    videoOutput: String = "gpu-next",
    gpuApi: String = "opengl",
    timingOptionsOwnedByUser: Boolean = false,
  ): Boolean =
    RifeResidentPlaybackPolicy.isEligible(
      rifeEnabled = rifeEnabled,
      hardwareDecodingEnabled = hardwareDecodingEnabled,
      modelAvailable = modelAvailable,
      videoOutput = videoOutput,
      gpuApi = gpuApi,
      timingOptionsOwnedByUser = timingOptionsOwnedByUser,
    )

  @Test
  fun selectsResidentPathOnlyForGpuNextOpenGlPresentationWithHardwareDecodeAndModel() {
    assertTrue(eligible())
    assertFalse(eligible(videoOutput = "gpu"))
    assertFalse(eligible(videoOutput = "gpu-next", gpuApi = "vulkan"))
    assertFalse(eligible(hardwareDecodingEnabled = false))
    assertFalse(eligible(modelAvailable = false))
    assertFalse(eligible(rifeEnabled = false))
  }

  @Test
  fun userOwnedInterpolationOrVideoSyncKeepsResidentRouteOff() {
    assertFalse(eligible(timingOptionsOwnedByUser = true))
  }
}
