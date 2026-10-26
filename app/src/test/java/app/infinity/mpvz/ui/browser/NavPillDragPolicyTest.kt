package app.infinity.mpvz.ui.browser

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class NavPillDragPolicyTest {
  @Test fun clampsFiniteAndInvalidOffsets() {
    assertEquals(0f, NavPillDragPolicy.clampOffset(-4f, 0f, 10f), 0f)
    assertEquals(10f, NavPillDragPolicy.clampOffset(15f, 0f, 10f), 0f)
    assertEquals(0f, NavPillDragPolicy.clampOffset(Float.NaN, 0f, 10f), 0f)
  }

  @Test fun snapsToNearestTabAndBounds() {
    assertEquals(1, NavPillDragPolicy.nearestTabIndex(1.49f, 3))
    assertEquals(2, NavPillDragPolicy.nearestTabIndex(1.6f, 3))
    assertEquals(0, NavPillDragPolicy.nearestTabIndex(-2f, 3))
    assertEquals(2, NavPillDragPolicy.nearestTabIndex(9f, 3))
  }

  @Test fun mapsReleaseXInBothLayoutDirections() {
    val centers = listOf(10f, 50f, 90f)
    assertEquals(2, NavPillDragPolicy.tabIndexAtX(82f, centers))
    assertEquals(0, NavPillDragPolicy.tabIndexAtX(82f, centers, isRtl = true))
    assertEquals(1.5f, NavPillDragPolicy.tabPositionAtX(70f, centers), 0.001f)
  }

  @Test fun tapThresholdDoesNotCountAsDrag() {
    assertFalse(NavPillDragPolicy.crossedTouchSlop(7.9f))
    assertTrue(NavPillDragPolicy.crossedTouchSlop(8f))
  }

  @Test fun dragDeltaFollowsFingerAndClampsAtEnds() {
    val centers = listOf(20f, 80f, 140f)
    // The pill follows the finger continuously: half way between tab 1 (80) and tab 2 (140).
    assertEquals(1.5f, NavPillDragPolicy.positionAfterDrag(80f, 30f, centers), 0.001f)
    assertEquals(1f, NavPillDragPolicy.positionAfterDrag(20f, 60f, centers), 0.001f)
    assertEquals(0f, NavPillDragPolicy.positionAfterDrag(20f, -500f, centers), 0.001f)
    assertEquals(2f, NavPillDragPolicy.positionAfterDrag(80f, 500f, centers), 0.001f)
  }

  @Test fun hoveredTabUsesNearestPillPosition() {
    assertEquals(1, NavPillDragPolicy.hoveredTabIndex(1.49f, 3))
    assertEquals(2, NavPillDragPolicy.hoveredTabIndex(1.51f, 3))
  }

  @Test fun flingAdvancesOneTabAndMirrorsInRtl() {
    assertEquals(2, NavPillDragPolicy.flingSnappedIndex(1f, 1200f, 4))
    assertEquals(0, NavPillDragPolicy.flingSnappedIndex(1f, -1200f, 4))
    assertEquals(0, NavPillDragPolicy.flingSnappedIndex(1f, 1200f, 4, isRtl = true))
    assertEquals(1, NavPillDragPolicy.flingSnappedIndex(1f, 100f, 4))
  }

  @Test
  fun slopIsDecidedOnceFromTotalTravelAndVerticalMovesAreReleased() {
    val slop = 24f
    assertEquals(NavPillDragPolicy.SlopDecision.Undecided, NavPillDragPolicy.classifySlop(10f, 4f, slop))
    // 1942 needed the overshoot past the slop to exceed the slop again; one slop is enough now.
    assertEquals(NavPillDragPolicy.SlopDecision.Horizontal, NavPillDragPolicy.classifySlop(25f, 3f, slop))
    assertEquals(NavPillDragPolicy.SlopDecision.Vertical, NavPillDragPolicy.classifySlop(20f, 30f, slop))
    assertEquals(NavPillDragPolicy.SlopDecision.Vertical, NavPillDragPolicy.classifySlop(30f, 30f, slop))
    assertEquals(NavPillDragPolicy.SlopDecision.Vertical, NavPillDragPolicy.classifySlop(Float.NaN, 0f, slop))
  }

  @Test
  fun overflowingPillScrollsToKeepTheLensVisible() {
    assertEquals(0f, NavPillDragPolicy.autoScrollDelta(lensCenter = 150f, scroll = 0f, viewport = 300f), 0f)
    assertEquals(36f, NavPillDragPolicy.autoScrollDelta(lensCenter = 280f, scroll = 0f, viewport = 300f), 0.001f)
    assertEquals(-26f, NavPillDragPolicy.autoScrollDelta(lensCenter = 130f, scroll = 100f, viewport = 300f), 0.001f)
    assertEquals(0f, NavPillDragPolicy.autoScrollDelta(lensCenter = 130f, scroll = 100f, viewport = 0f), 0f)
  }
}
