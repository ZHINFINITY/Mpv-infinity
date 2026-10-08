package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class Media3FlowGeometryTest {
  @Test
  fun fullHdSourceUsesReducedMotionGrid() {
    assertEquals(Media3FlowMotionSize(480, 270), Media3FlowGeometry.motionSize(1920, 1080, 480))
  }

  @Test
  fun portraitSourceFitsTheSameMotionCapWithoutChangingAspectRatio() {
    assertEquals(Media3FlowMotionSize(270, 480), Media3FlowGeometry.motionSize(1080, 1920, 480))
  }

  @Test
  fun sourcesBelowCapKeepTheirNativeMotionDimensions() {
    assertEquals(Media3FlowMotionSize(320, 180), Media3FlowGeometry.motionSize(320, 180, 480))
  }
}
