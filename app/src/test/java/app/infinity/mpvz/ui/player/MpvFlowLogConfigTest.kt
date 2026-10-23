/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvFlowLogConfigTest {
  @Test
  fun mpvFlowKeepsCadenceInfoForFpsAndSuppressesVerboseGlobalNoise() {
    assertEquals(
      "all=warn,vo/gpu-next=info",
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
