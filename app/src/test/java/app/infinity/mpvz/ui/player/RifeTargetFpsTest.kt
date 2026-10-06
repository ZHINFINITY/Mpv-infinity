/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.DEFAULT_RIFE_TARGET_FPS
import app.infinity.mpvz.preferences.RIFE_TARGET_FPS_OPTIONS
import app.infinity.mpvz.preferences.normalizeRifeTargetFps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RifeTargetFpsTest {
  @Test
  fun targetFrameRatesAreAvailableAndDefaultToThirty() {
    assertEquals(listOf(30, 48, 60), RIFE_TARGET_FPS_OPTIONS)
    assertEquals(30, DEFAULT_RIFE_TARGET_FPS)
  }

  @Test
  fun unsupportedStoredValuesNormalizeToNearestSupportedRate() {
    assertEquals(30, normalizeRifeTargetFps(0))
    assertEquals(48, normalizeRifeTargetFps(50))
    assertEquals(60, normalizeRifeTargetFps(120))
  }
}
