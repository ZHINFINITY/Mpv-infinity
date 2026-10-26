package app.infinity.mpvz.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGlassLensPolicyTest {
  @Test
  fun activeLensPopsAboveBarAndMagnifiesWithinBounds() {
    val geometry = NavigationGlassLensPolicy.geometry(20f, 100f, active = true)
    assertTrue(geometry.height > NavigationGlassLensPolicy.SettledHeight)
    assertTrue(geometry.offsetY < 0f)
    assertTrue(geometry.magnification in 1.15f..1.25f)
  }

  @Test
  fun inactiveLensReturnsToOriginalBarGeometry() {
    assertEquals(
      NavigationGlassLensPolicy.Geometry(20f, 100f, 44f, 0f, 1f),
      NavigationGlassLensPolicy.geometry(20f, 100f, active = false),
    )
  }

  @Test
  fun malformedInputsRemainFinite() {
    val geometry = NavigationGlassLensPolicy.geometry(Float.NaN, -10f, active = true)
    assertTrue(geometry.left.isFinite())
    assertTrue(geometry.width >= 0f)
    assertTrue(geometry.height.isFinite())
    assertTrue(geometry.magnification.isFinite())
  }
}
