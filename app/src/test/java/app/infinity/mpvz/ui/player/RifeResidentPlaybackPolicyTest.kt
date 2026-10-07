/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
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

  @Test
  fun residentRouteRequiresEveryLibmpvOptionSetterToReturnSuccess() {
    val accepted =
      RifeResidentPlaybackPolicy.OptionResults(
        resident = 0,
        modelDir = 0,
        targetFps = 0,
        maxDimension = 0,
        residentDisable = 0,
        residentEnable = 0,
      )
    assertTrue(accepted.allAccepted)
    assertTrue(
      RifeResidentPlaybackPolicy.shouldUseResidentRoute(candidate = true, results = accepted),
    )
    val optionNotFound =
      RifeResidentPlaybackPolicy.OptionResults(
        resident = 0,
        modelDir = -5,
        targetFps = 0,
        maxDimension = 0,
        residentDisable = 0,
      )
    assertFalse(optionNotFound.allAccepted)
    assertFalse(
      RifeResidentPlaybackPolicy.shouldUseResidentRoute(candidate = true, results = optionNotFound),
    )
    assertFalse(
      RifeResidentPlaybackPolicy.shouldUseResidentRoute(candidate = false, results = accepted),
    )
    assertFalse(
      RifeResidentPlaybackPolicy.OptionResults(
        resident = 0,
        modelDir = 0,
        targetFps = -2,
        maxDimension = 0,
      ).allAccepted,
    )
  }

  @Test
  fun activeFilterPathRequiresSuccessfulOptionSetting() {
    assertEquals(
      "resident_vulkan_ncnn",
      RifeResidentPlaybackPolicy.activeFilterPath(
        residentSelected = true,
        cpuFilterRequested = false,
        cpuFilterSetResult = null,
      ),
    )
    assertEquals(
      "cpu_rgb24_filter",
      RifeResidentPlaybackPolicy.activeFilterPath(
        residentSelected = false,
        cpuFilterRequested = true,
        cpuFilterSetResult = 0,
      ),
    )
    assertEquals(
      "none",
      RifeResidentPlaybackPolicy.activeFilterPath(
        residentSelected = false,
        cpuFilterRequested = true,
        cpuFilterSetResult = -5,
      ),
    )
    assertEquals(
      "none",
      RifeResidentPlaybackPolicy.activeFilterPath(
        residentSelected = false,
        cpuFilterRequested = true,
        cpuFilterSetResult = null,
      ),
    )
  }
}
