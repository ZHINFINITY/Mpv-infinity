/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimeOverlayTest {
  @Test fun percentilesUseSortedAggregatedDurations() {
    val stats = FrameTimeStats(4, 1, listOf(4f, 1f, 3f, 2f))
    assertEquals(2f, stats.percentile(.50f), 0.001f)
    assertEquals(4f, stats.percentile(.90f), 0.001f)
    assertEquals(25f, stats.jankyPercent, 0.001f)
  }

  @Test fun jankThresholdFollowsDisplayRefreshRate() {
    assertEquals(8.333f, FrameTimePolicy.frameBudgetMs(120f), 0.001f)
    assertTrue(FrameTimePolicy.isJank(8_334_000L, 120f))
    assertFalse(FrameTimePolicy.isJank(8_333_000L, 120f))
    assertTrue(FrameTimePolicy.isJank(16_668_000L, 60f))
  }

  @Test fun accumulatorPublishesAndCountsOnlyOverBudgetFrames() {
    val accumulator = FrameTimeAccumulator(10f)
    accumulator.add(5_000_000L)
    accumulator.add(11_000_000L)
    val stats = accumulator.snapshot()
    assertEquals(2, stats.frameCount)
    assertEquals(1, stats.jankyFrames)
  }
}
