package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowCadenceTest {
  @Test
  fun outputTimestampsUseAbsoluteOrdinalTicksWithoutLongRunDrift() {
    assertEquals(16_667L, Media3FlowCadence.outputTimestampUs(1, 0, 60, 1f))
    assertEquals(33_333L, Media3FlowCadence.outputTimestampUs(2, 0, 60, 1f))
    assertEquals(100_000_000L, Media3FlowCadence.outputTimestampUs(6_000, 0, 60, 1f))
  }

  @Test
  fun outputTicksRespectPositionAndPlaybackSpeed() {
    assertEquals(3L, Media3FlowCadence.outputTick(50_000, 0, 60, 1f))
    assertEquals(3L, Media3FlowCadence.outputTick(100_000, 0, 60, 2f))
    assertNull(Media3FlowCadence.outputTick(9, 10, 60, 1f))
    assertNull(Media3FlowCadence.outputTick(1, 0, 60, 0f))
  }

  @Test
  fun sourceRateUsesMedianPositiveTimestampDeltaAndIgnoresDiscontinuities() {
    val fps = Media3FlowCadence.estimateSourceFps(
      listOf(0L, 41_667L, 83_334L, 124_999L, 1_000L, 166_667L),
    )
    assertEquals(24f, fps, 0.05f)
    assertEquals(0f, Media3FlowCadence.estimateSourceFps(listOf(5L, 5L, 4L)), 0f)
  }

  @Test
  fun interpolationOnlyUsesStrictlyIncreasingBracketedSourceFrames() {
    assertEquals(0.25f, Media3FlowCadence.interpolationAlpha(0L, 40_000L, 10_000L)!!, 0.0001f)
    assertNull(Media3FlowCadence.interpolationAlpha(10L, 10L, 10L))
    assertNull(Media3FlowCadence.interpolationAlpha(0L, 40_000L, 0L))
    assertNull(Media3FlowCadence.interpolationAlpha(0L, 40_000L, 40_000L))
    assertNull(Media3FlowCadence.interpolationAlpha(0L, 40_000L, 50_000L))
  }

  @Test
  fun sourceFramesAtOrAboveDisplayRateBypassSynthesis() {
    assertFalse(Media3FlowCadence.needsInterpolation(8_333L, 60, 1f))
    assertFalse(Media3FlowCadence.needsInterpolation(16_667L, 60, 1f))
    assertTrue(Media3FlowCadence.needsInterpolation(41_667L, 60, 1f))
    assertTrue(Media3FlowCadence.needsInterpolation(41_667L, 60, 2f))
  }

  @Test
  fun missedCadenceTicksAreCountedWithoutCreatingUnboundedCatchupWork() {
    assertEquals(0L, Media3FlowCadence.skippedTicks(-1L, 0L))
    assertEquals(0L, Media3FlowCadence.skippedTicks(4L, 5L))
    assertEquals(2L, Media3FlowCadence.skippedTicks(4L, 7L))
  }
}
