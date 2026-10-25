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
    assertEquals(1f, NavPillDragPolicy.positionAfterDrag(80f, 30f, centers), 0.001f)
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
}
