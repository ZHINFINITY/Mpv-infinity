/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.DEFAULT_MPVFLOW_TARGET_FPS
import app.infinity.mpvz.preferences.MPVFLOW_TARGET_FPS_OPTIONS
import app.infinity.mpvz.preferences.effectiveMpvFlowMaxDimension
import app.infinity.mpvz.preferences.effectiveMpvFlowTargetFps
import app.infinity.mpvz.preferences.normalizeMpvFlowTargetFps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

  @Test
  fun defaultQualityUsesTargetRateSpecificMotionAnalysisBudgets() {
    assertEquals(listOf(48, 60, 72, 90, 96, 120, 144), MPVFLOW_TARGET_FPS_OPTIONS)
    assertEquals(576, effectiveMpvFlowMaxDimension(60))
    assertEquals(432, effectiveMpvFlowMaxDimension(72))
    assertEquals(432, effectiveMpvFlowMaxDimension(90))
    assertEquals(384, effectiveMpvFlowMaxDimension(96))
    assertEquals(384, effectiveMpvFlowMaxDimension(120))
    assertEquals(304, effectiveMpvFlowMaxDimension(144))
  }

  @Test
  fun qualityProfileControlsTheAnalysisRangeAtHighRates() {
    assertEquals(320, effectiveMpvFlowMaxDimension(120, 0))
    assertEquals(384, effectiveMpvFlowMaxDimension(120, 60))
    assertEquals(448, effectiveMpvFlowMaxDimension(120, 100))
    assertEquals(320, effectiveMpvFlowMaxDimension(96, 0))
    assertEquals(432, effectiveMpvFlowMaxDimension(96, 100))
    assertEquals(288, effectiveMpvFlowMaxDimension(144, 0))
    assertEquals(320, effectiveMpvFlowMaxDimension(144, 100))
  }

  @Test
  fun qualityProfileIsMonotonicAndTileAlignedForEveryTargetRate() {
    for (fps in MPVFLOW_TARGET_FPS_OPTIONS) {
      val performance = effectiveMpvFlowMaxDimension(fps, 0)
      val balanced = effectiveMpvFlowMaxDimension(fps, 60)
      val quality = effectiveMpvFlowMaxDimension(fps, 100)
      assertTrue("profile must increase analysis size at $fps fps", performance < balanced)
      assertTrue("profile must reach the quality end at $fps fps", balanced < quality)
      assertEquals(0, performance % 16)
      assertEquals(0, balanced % 16)
      assertEquals(0, quality % 16)
    }
  }
}
