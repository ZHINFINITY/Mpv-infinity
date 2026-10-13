/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvFlowLogConfigTest {
  @Test
  fun mpvFlowKeepsInfoGloballyAndEnablesOnlyLibplaceboVerboseDiagnostics() {
    assertEquals(
      "all=info,vo/gpu-next/libplacebo=v",
      MpvFlowLogConfig.messageLevelOption(
        mpvFlowEnabled = true,
        frameInterpolationEnabled = true,
        verboseLogging = false,
      ),
    )
  }

  @Test
  fun nonFlowLoggingLevelsRemainUnchanged() {
    assertEquals(
      "all=info",
      MpvFlowLogConfig.messageLevelOption(
        mpvFlowEnabled = false,
        frameInterpolationEnabled = true,
        verboseLogging = false,
      ),
    )
    assertEquals(
      "all=v",
      MpvFlowLogConfig.messageLevelOption(
        mpvFlowEnabled = false,
        frameInterpolationEnabled = false,
        verboseLogging = true,
      ),
    )
    assertEquals(
      "all=warn",
      MpvFlowLogConfig.messageLevelOption(
        mpvFlowEnabled = false,
        frameInterpolationEnabled = false,
        verboseLogging = false,
      ),
    )
  }
}
