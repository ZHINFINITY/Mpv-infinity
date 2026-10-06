/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.DEFAULT_RIFE_PROCESSING_RESOLUTION
import app.infinity.mpvz.preferences.DEFAULT_RIFE_TARGET_FPS
import app.infinity.mpvz.preferences.RIFE_PROCESSING_RESOLUTION_OPTIONS
import app.infinity.mpvz.preferences.RIFE_TARGET_FPS_OPTIONS
import app.infinity.mpvz.preferences.normalizeRifeProcessingResolution
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

  @Test
  fun processingResolutionSupportsAutomaticCapsAndOriginalResolution() {
    assertEquals(listOf(-1, 0, 480, 720, 1080), RIFE_PROCESSING_RESOLUTION_OPTIONS)
    assertEquals(0, DEFAULT_RIFE_PROCESSING_RESOLUTION)
    assertEquals(0, normalizeRifeProcessingResolution(0))
    assertEquals(-1, normalizeRifeProcessingResolution(-1))
    assertEquals(480, normalizeRifeProcessingResolution(500))
    assertEquals(720, normalizeRifeProcessingResolution(800))
  }
}
