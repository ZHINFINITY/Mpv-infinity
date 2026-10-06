/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererBackendPolicyTest {
  @Test
  fun interpolationUsesCopyModeHardwareDecodingForCpuVisibleFrames() {
    assertEquals("mediacodec-copy,no", RendererBackendPolicy.interpolationHwdecMode())
  }
}
