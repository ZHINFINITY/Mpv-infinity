/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import app.infinity.mpvz.ui.player.controls.components.GlassControlGeometryPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassControlGeometryPolicyTest {
  @Test fun switchThumbIsOnlyModestlyLargerThanPreviousGlassThumb() {
    assertTrue(GlassControlGeometryPolicy.isSlightlyLarger(
      GlassControlGeometryPolicy.switchThumbWidthDp,
      GlassControlGeometryPolicy.oldSwitchThumbWidthDp,
    ))
    assertTrue(GlassControlGeometryPolicy.isSlightlyLarger(
      GlassControlGeometryPolicy.switchThumbHeightDp,
      GlassControlGeometryPolicy.oldSwitchThumbHeightDp,
    ))
  }

  @Test fun sliderThumbIsOnlyModestlyLargerThanPreviousGlassThumb() {
    assertTrue(GlassControlGeometryPolicy.isSlightlyLarger(
      GlassControlGeometryPolicy.sliderThumbWidthDp,
      GlassControlGeometryPolicy.oldSliderThumbWidthDp,
    ))
    assertTrue(GlassControlGeometryPolicy.isSlightlyLarger(
      GlassControlGeometryPolicy.sliderThumbHeightDp,
      GlassControlGeometryPolicy.oldSliderThumbHeightDp,
    ))
  }
}
