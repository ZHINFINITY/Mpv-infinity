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

  @Test fun switchCapsuleThumbStaysInsideItsGrooveWhenOnAndOff() {
    val half = GlassControlGeometryPolicy.switchThumbWidthDp / 2f
    val inset = GlassControlGeometryPolicy.materialSwitchThumbCentreInsetDp
    val overhang = GlassControlGeometryPolicy.switchTrackOverhangDp()
    // Off: thumb centre 16dp from the start; on: 16dp from the end of Material's 52dp track.
    val offLeftGap = (inset - half) - (-overhang)
    val onRightGap = (52f + overhang) - (52f - inset + half)
    assertTrue("off thumb pokes out of the groove: $offLeftGap", offLeftGap >= GlassControlGeometryPolicy.switchThumbEndGapDp - 0.001f)
    assertTrue("on thumb pokes out of the groove: $onRightGap", onRightGap >= GlassControlGeometryPolicy.switchThumbEndGapDp - 0.001f)
    // A thumb that already fits needs no overhang.
    assertTrue(GlassControlGeometryPolicy.switchTrackOverhangDp(thumbWidthDp = 24f) == 0f)
  }

  @Test fun sliderGrooveIsDeeperThanBeforeButStillAThinTrack() {
    assertTrue(GlassControlGeometryPolicy.sliderTrackHeightDp > GlassControlGeometryPolicy.oldSliderTrackHeightDp)
    assertTrue(GlassControlGeometryPolicy.sliderTrackHeightDp <= 6f)
  }
}
