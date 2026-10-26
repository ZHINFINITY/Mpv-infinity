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

  @Test fun innerRimIsSmallAndBounded() {
    assertEquals(0f, GlassControlPolicy.innerRimAlpha(-1f), 0f)
    assertEquals(0.045f, GlassControlPolicy.innerRimAlpha(1f), 0f)
    assertEquals(0.09f, GlassControlPolicy.innerRimAlpha(8f), 0f)
  }

  @Test fun sliderThumbBulgesOnlyWhilePressed() {
    assertEquals(1.2f, GlassSliderPolicy.stretch(true), 0f)
    assertEquals(0.92f, GlassSliderPolicy.squash(true), 0f)
    assertEquals(1f, GlassSliderPolicy.stretch(false), 0f)
    assertEquals(1f, GlassSliderPolicy.stretch(true, reducedMotion = true), 0f)
  }

  @Test fun steppedSliderTicksIncludeEveryStop() {
    assertEquals(listOf(0f, .25f, .5f, .75f, 1f), GlassSliderPolicy.tickPositions(3))
    assertTrue(GlassSliderPolicy.tickPositions(0).isEmpty())
  }

  @Test fun hapticBucketsAreRateLimitedToTwentyForContinuousSliders() {
    assertEquals(0, GlassSliderPolicy.hapticBucket(0f, 0f..1f, 0))
    assertEquals(10, GlassSliderPolicy.hapticBucket(.5f, 0f..1f, 0))
    assertEquals(20, GlassSliderPolicy.hapticBucket(1f, 0f..1f, 0))
  }

  @Test fun steppedHapticBucketsMatchSteps() {
    assertEquals(0, GlassSliderPolicy.hapticBucket(0f, 0f..10f, 4))
    assertEquals(2, GlassSliderPolicy.hapticBucket(5f, 0f..10f, 4))
    assertEquals(5, GlassSliderPolicy.hapticBucket(10f, 0f..10f, 4))
  }
}
