/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassControlPolicyTest {
  @Test fun depthIsFiniteAndBounded() {
    assertEquals(1f, GlassControlPolicy.depth(Float.NaN), 0f)
    assertEquals(1f, GlassControlPolicy.depth(Float.POSITIVE_INFINITY), 0f)
    assertEquals(0f, GlassControlPolicy.depth(-2f), 0f)
    assertEquals(2f, GlassControlPolicy.depth(8f), 0f)
  }

  @Test fun checkedThumbGetsStrongerHighlightThanUnchecked() {
    assertTrue(GlassControlPolicy.highlightAlpha(0.5f, true, true) > GlassControlPolicy.highlightAlpha(0.5f, true, false))
  }

  @Test fun disabledControlsRetainMutedOptics() {
    assertTrue(GlassControlPolicy.highlightAlpha(0.5f, false, true) < GlassControlPolicy.highlightAlpha(0.5f, true, true))
    assertTrue(GlassControlPolicy.shadowAlpha(1f, false) < GlassControlPolicy.shadowAlpha(1f, true))
    assertEquals(0.22f, GlassControlPolicy.thumbBorderAlpha(false, true), 0f)
  }
}
