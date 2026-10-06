/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.DEFAULT_MPVFLOW_TARGET_FPS
import app.infinity.mpvz.preferences.MPVFLOW_TARGET_FPS_OPTIONS
import app.infinity.mpvz.preferences.effectiveMpvFlowTargetFps
import app.infinity.mpvz.preferences.normalizeMpvFlowTargetFps
import org.junit.Assert.assertEquals
import org.junit.Test

class MpvFlowTargetFpsTest {
  @Test
  fun targetRatesMatchTheOnDeviceChoicesAndDefaultToSixty() {
    assertEquals(listOf(48, 60, 72, 90, 96, 120, 144), MPVFLOW_TARGET_FPS_OPTIONS)
    assertEquals(60, DEFAULT_MPVFLOW_TARGET_FPS)
  }

  @Test
  fun unsupportedStoredValuesNormalizeToNearestChoice() {
    assertEquals(48, normalizeMpvFlowTargetFps(50))
    assertEquals(96, normalizeMpvFlowTargetFps(100))
    assertEquals(144, normalizeMpvFlowTargetFps(200))
  }

  @Test
  fun effectiveRateDoesNotExceedRequestedOrDisplayRefresh() {
    assertEquals(60, effectiveMpvFlowTargetFps(144, 60f))
    assertEquals(90, effectiveMpvFlowTargetFps(120, 90f))
    assertEquals(120, effectiveMpvFlowTargetFps(144, 120f))
    assertEquals(144, effectiveMpvFlowTargetFps(144, 144f))
    assertEquals(72, effectiveMpvFlowTargetFps(120, 75f))
    assertEquals(40, effectiveMpvFlowTargetFps(60, 40f))
  }

  @Test
  fun unknownDisplayRefreshPreservesRequestedRate() {
    assertEquals(120, effectiveMpvFlowTargetFps(120, 0f))
    assertEquals(120, effectiveMpvFlowTargetFps(120, Float.NaN))
  }
}
