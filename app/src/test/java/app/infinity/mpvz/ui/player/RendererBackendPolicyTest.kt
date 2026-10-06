/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererBackendPolicyTest {
  @Test
  fun cpuFilterUsesMediaCodecCopyModeWhenHardwareDecodingIsEnabled() {
    assertEquals(
      "mediacodec-copy,no",
      RendererBackendPolicy.preferredHwdecModeForCpuFilter(hardwareDecodingEnabled = true),
    )
  }

  @Test
  fun cpuFilterRespectsSoftwareDecodingPreference() {
    assertEquals(
      "no",
      RendererBackendPolicy.preferredHwdecModeForCpuFilter(hardwareDecodingEnabled = false),
    )
  }
}
