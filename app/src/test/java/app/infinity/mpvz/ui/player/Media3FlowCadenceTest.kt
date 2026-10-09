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
  fun outputTickTimestampNeverRunsAheadOfThePlayerPosition() {
    for (speed in listOf(0.5f, 1f, 2f, 4f)) {
      for (positionUs in 0L..2_000_000L step 997L) {
        val tick = Media3FlowCadence.outputTick(positionUs, 0L, 72, speed)!!
        val targetPtsUs = Media3FlowCadence.outputTimestampUs(tick, 0L, 72, speed)!!
        assertTrue("target $targetPtsUs exceeds position $positionUs at speed $speed", targetPtsUs <= positionUs)
      }
    }
  }

  @Test
  fun sourceRateUsesMedianPositiveTimestampDeltaAndIgnoresDiscontinuities() {
    val fps = Media3FlowCadence.estimateSourceFps(
      listOf(0L, 41_667L, 83_334L, 124_999L, 1_000L, 166_667L),
    )
    assertEquals(24f, fps, 0.05f)
    assertEquals(12f, Media3FlowCadence.estimateSourceFps(listOf(0L, 83_333L, 166_667L)), 0.05f)
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
    assertFalse(Media3FlowCadence.needsInterpolation(16_666L, 60, 1f))
    assertTrue(Media3FlowCadence.needsInterpolation(16_667L, 60, 1f))
    assertTrue(Media3FlowCadence.needsInterpolation(41_667L, 60, 1f))
    assertTrue(Media3FlowCadence.needsInterpolation(41_667L, 60, 2f))
  }

  @Test
  fun missedCadenceTicksAreCountedWithoutCreatingUnboundedCatchupWork() {
    assertEquals(0L, Media3FlowCadence.skippedTicks(-1L, 0L))
    assertEquals(0L, Media3FlowCadence.skippedTicks(4L, 5L))
    assertEquals(2L, Media3FlowCadence.skippedTicks(4L, 7L))
  }

  @Test
  fun motionEstimateUsesPairGpuTimeWhenAvailableAndSubmitTimeWhilePending() {
    assertEquals(3f, Media3FlowCadence.motionEstimateMs(3f, null), 0f)
    assertEquals(7f, Media3FlowCadence.motionEstimateMs(3f, 7f), 0f)
    assertEquals(3f, Media3FlowCadence.motionEstimateMs(3f, 2f), 0f)
  }

  @Test
  fun decoderInputWaitsForAFreeGpuFrameSlotAndCompletedSurfaceCapture() {
    assertTrue(Media3FlowCadence.canReleaseNextDecoderFrame(0, 3, inputInFlight = false))
    assertTrue(Media3FlowCadence.canReleaseNextDecoderFrame(2, 3, inputInFlight = false))
    assertFalse(Media3FlowCadence.canReleaseNextDecoderFrame(3, 3, inputInFlight = false))
    assertFalse(Media3FlowCadence.canReleaseNextDecoderFrame(2, 3, inputInFlight = true))
    assertFalse(Media3FlowCadence.canReleaseNextDecoderFrame(4, 3, inputInFlight = false))
    assertFalse(Media3FlowCadence.canReleaseNextDecoderFrame(0, 0, inputInFlight = false))
  }

  @Test
  fun decoderFramesAreReleasedOnThePlaybackClockNotImmediately() {
    assertEquals(
      1_040_000_000L,
      Media3FlowCadence.inputReleaseTimeNs(
        framePtsUs = 50_000L,
        clockPositionUs = 10_000L,
        nowNs = 1_000_000_000L,
        speed = 1f,
        maxLookAheadUs = 100_000L,
      ),
    )
    assertEquals(
      1_020_000_000L,
      Media3FlowCadence.inputReleaseTimeNs(
        framePtsUs = 50_000L,
        clockPositionUs = 10_000L,
        nowNs = 1_000_000_000L,
        speed = 2f,
        maxLookAheadUs = 100_000L,
      ),
    )
  }

  @Test
  fun decoderFramesBeyondTheBoundedLookAheadRemainQueued() {
    assertNull(
      Media3FlowCadence.inputReleaseTimeNs(
        framePtsUs = 160_000L,
        clockPositionUs = 10_000L,
        nowNs = 1_000_000_000L,
        speed = 1f,
        maxLookAheadUs = 100_000L,
      ),
    )
    assertEquals(
      1_075_000_000L,
      Media3FlowCadence.inputReleaseTimeNs(
        framePtsUs = 160_000L,
        clockPositionUs = 10_000L,
        nowNs = 1_000_000_000L,
        speed = 2f,
        maxLookAheadUs = 100_000L,
      ),
    )
  }

  @Test
  fun fallbackNeverSelectsASourceFrameAheadOfTheRequestedPresentationTime() {
    val ptsUs = longArrayOf(100_000L, 141_667L, 183_334L)

    assertNull(Media3FlowCadence.sourceFrameIndexAtOrBefore(ptsUs, 99_999L))
    assertEquals(0, Media3FlowCadence.sourceFrameIndexAtOrBefore(ptsUs, 100_000L))
    assertEquals(1, Media3FlowCadence.sourceFrameIndexAtOrBefore(ptsUs, 180_000L))
    assertEquals(2, Media3FlowCadence.sourceFrameIndexAtOrBefore(ptsUs, 183_334L))
  }
}
