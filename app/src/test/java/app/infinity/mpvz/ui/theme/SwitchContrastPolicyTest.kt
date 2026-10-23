/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class SwitchContrastPolicyTest {
  @Test fun darkAndAmoledSurfacesUseLightCheckedTrack() {
    for (luminance in listOf(0f, .02f, .20f)) {
      val c = SwitchContrastPolicy.forSurfaceLuminance(luminance)
      // Off-white #F7F7F7: reads as white on dark/AMOLED without a harsh pure-white glare.
      assertEquals(0xF7 / 255f, c.checkedTrack.red, 0.002f)
      assertEquals(0xF7 / 255f, c.checkedTrack.green, 0.002f)
      assertEquals(0xF7 / 255f, c.checkedTrack.blue, 0.002f)
      assertTrue(c.checkedTrack.contrastAgainst(c.checkedThumb) >= 3f)
    }
  }
  @Test fun lightSurfaceUsesDarkCheckedTrackAndLightThumb() {
    val c = SwitchContrastPolicy.forSurfaceLuminance(.9f)
    assertTrue(c.checkedTrack.red < .1f)
    assertTrue(c.checkedThumb.red > .9f)
  }
  @Test fun uncheckedStateIsDistinctAndOutlined() {
    val c = SwitchContrastPolicy.forSurfaceLuminance(0f)
    assertTrue(c.uncheckedTrack.alpha < c.checkedTrack.alpha)
    assertTrue(c.uncheckedBorder.alpha >= .7f)
    assertTrue(c.uncheckedThumb.alpha > .9f)
  }
  private fun androidx.compose.ui.graphics.Color.contrastAgainst(other: androidx.compose.ui.graphics.Color): Float =
    (maxOf(red, other.red) + .05f) / (minOf(red, other.red) + .05f)
}
