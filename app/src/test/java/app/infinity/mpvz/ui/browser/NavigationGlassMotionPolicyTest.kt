package app.infinity.mpvz.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGlassMotionPolicyTest {
  @Test
  fun dragStretchIsBoundedAndReturnsToNeutralAtEndpoints() {
    val middle = NavigationGlassMotionPolicy.dragGeometry(10f, 100f, 0.5f, 1)
    assertTrue(middle.width <= 118f)
    assertTrue(middle.width >= 100f)
    assertTrue(middle.deformation in 0f..1f)
    assertEquals(0f, NavigationGlassMotionPolicy.dragGeometry(10f, 100f, 0f, 1).deformation, 0f)
    assertEquals(0f, NavigationGlassMotionPolicy.dragGeometry(10f, 100f, 1f, 1).deformation, 0f)
  }

  @Test
  fun directionIsSignedAndClamped() {
    assertEquals(-1, NavigationGlassMotionPolicy.clampDirection(-0.2f))
    assertEquals(1, NavigationGlassMotionPolicy.clampDirection(0.2f))
    assertEquals(0, NavigationGlassMotionPolicy.clampDirection(0f))
    assertTrue(NavigationGlassMotionPolicy.dragGeometry(0f, 50f, 0.5f, -1).deformation < 0f)
  }

  @Test
  fun settledMotionHasNoDeformationOrOvershoot() {
    assertEquals(
      NavigationGlassMotionPolicy.Geometry(4f, 80f, 0f),
      NavigationGlassMotionPolicy.settledGeometry(4f, 80f),
    )
  }

  @Test
  fun malformedGeometryCannotProduceNaNOrNegativeWidth() {
    val result = NavigationGlassMotionPolicy.dragGeometry(Float.NaN, -10f, Float.NaN, 999)
    assertEquals(NavigationGlassMotionPolicy.Geometry(0f, 0f, 0f), result)
    assertEquals(0, NavigationGlassMotionPolicy.clampDirection(Float.POSITIVE_INFINITY))
    assertEquals(NavigationGlassMotionPolicy.Geometry(0f, 0f, 0f),
      NavigationGlassMotionPolicy.settledGeometry(Float.NaN, Float.POSITIVE_INFINITY))
  }

  @Test
  fun tailStretchesBehindTravelAndRestingSelectionDoesNotStretch() {
    val right = NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, 1)
    val left = NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, -1)
    assertTrue(right.left < 20f)
    assertEquals(20f, left.left, 0f)
    assertEquals(100f, NavigationGlassMotionPolicy.dragGeometry(20f, 100f, 0.5f, 0).width, 0f)
  }
}
