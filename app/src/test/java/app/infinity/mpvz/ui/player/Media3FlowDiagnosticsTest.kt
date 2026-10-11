package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Media3FlowDiagnosticsTest {
  @Test
  fun timingSummaryUsesValidNanosecondsAndReportsAverageP95AndMaximumInMicroseconds() {
    val summary = Media3FlowDiagnosticMath.summarizeNanoseconds(
      listOf(1_000L, 2_000L, 3_000L, 4_000L, 10_000L, 0L, -1L),
    )

    assertEquals(4.0, summary.averageUs!!, 0.0001)
    assertEquals(10.0, summary.p95Us!!, 0.0001)
    assertEquals(10.0, summary.maximumUs!!, 0.0001)
    assertEquals(5, summary.sampleCount)
  }

  @Test
  fun timingSummaryIsEmptyWhenNoPositiveValidSamplesExist() {
    val summary = Media3FlowDiagnosticMath.summarizeNanoseconds(listOf(0L, -1L))

    assertNull(summary.averageUs)
    assertNull(summary.p95Us)
    assertNull(summary.maximumUs)
    assertEquals(0, summary.sampleCount)
  }

  @Test
  fun vulkanTimerStatesDistinguishUnsupportedPendingAndCompletedResults() {
    assertEquals(
      "unsupported_timestamp_queue",
      Media3FlowDiagnosticMath.vulkanTimerStageStatus(0, true, 3L, 0L),
    )
    assertEquals(
      "pending_or_dropped",
      Media3FlowDiagnosticMath.vulkanTimerStageStatus(32, true, 2L, 0L),
    )
    assertEquals(
      "ready",
      Media3FlowDiagnosticMath.vulkanTimerStageStatus(32, true, 2L, 1L),
    )
    assertEquals(
      "unsupported_libplacebo_timer",
      Media3FlowDiagnosticMath.vulkanTimerStageStatus(32, false, 0L, 0L),
    )
  }

  @Test
  fun vulkanSnapshotReportsStageAveragesAndUnresolvedQueriesWithoutCallingThemExactPending() {
    val snapshot = LongArray(23).apply {
      this[0] = 1L
      this[1] = 32L
      this[2] = 4L
      for (stage in 0 until 4) {
        val offset = 3 + stage * 5
        this[offset] = 1L
      }
      val preprocessOffset = 3
      this[preprocessOffset + 1] = 2L
      this[preprocessOffset + 2] = 2L
      this[preprocessOffset + 3] = 1_000_000L
      this[preprocessOffset + 4] = 900_000L
      val motionOffset = 3 + 2 * 5
      this[motionOffset + 1] = 3L
      this[motionOffset + 2] = 0L
    }

    val timings = Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(snapshot)

    assertEquals("ready", timings.status)
    assertEquals(32, timings.timestampValidBits)
    assertEquals("ready", timings.preprocess.status)
    assertEquals(2L, timings.preprocess.completedSamples)
    assertEquals(500.0, timings.preprocess.averageUs!!, 0.001)
    assertEquals(900.0, timings.preprocess.maximumUs!!, 0.001)
    assertEquals("pending_or_dropped", timings.motion.status)
    assertEquals(3L, timings.motion.pendingOrDropped)
    assertNull(timings.motion.averageUs)
  }

  @Test
  fun vulkanSnapshotNamesUnsupportedQueueAndRejectsUnknownSchema() {
    val unsupported = LongArray(23).apply {
      this[0] = 1L
      this[1] = 0L
      this[2] = 4L
      for (stage in 0 until 4) this[3 + stage * 5] = 1L
    }
    assertEquals(
      "unsupported_timestamp_queue",
      Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(unsupported).status,
    )
    assertEquals(
      "unsupported_snapshot_schema",
      Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(longArrayOf(2L)).status,
    )
    assertEquals(
      "snapshot_unavailable",
      Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(null).status,
    )
  }

  @Test
  fun coverageReportsWarpFallbackAndStaticBlendCountsAndPercentages() {
    val coverage = Media3FlowDiagnosticMath.summarizeCoverage(
      listOf(
        FlowCoverageSample(
          motionWarpPixels = 80L,
          sourceFrameFallbackPixels = 20L,
          staticBlendPixels = 0L,
          staticVectorLikelyMotionSamples = 15L,
          staticVectorUncertainMotionSamples = 5L,
          staticVectorNearZeroConfidentSamples = 15L,
          staticVectorNearZeroUncertainSamples = 5L,
          interframeChangedPixels = 10L,
          interframeChangedWarpPixels = 8L,
          interframeChangedSourceFallbackPixels = 2L,
          interframeChangedStaticBlendPixels = 0L,
        ),
        FlowCoverageSample(
          motionWarpPixels = 40L,
          sourceFrameFallbackPixels = 20L,
          staticBlendPixels = 40L,
          staticVectorLikelyMotionSamples = 5L,
          staticVectorUncertainMotionSamples = 10L,
          staticVectorNearZeroConfidentSamples = 2L,
          staticVectorNearZeroUncertainSamples = 3L,
          interframeChangedPixels = 10L,
          interframeChangedWarpPixels = 8L,
          interframeChangedSourceFallbackPixels = 1L,
          interframeChangedStaticBlendPixels = 1L,
        ),
      ),
    )

    assertEquals(2, coverage.samples)
    assertEquals(200L, coverage.sampledPixels)
    assertEquals(120L, coverage.motionWarpPixels)
    assertEquals(40L, coverage.sourceFrameFallbackPixels)
    assertEquals(40L, coverage.staticBlendPixels)
    assertEquals(60f, coverage.motionWarpPercent!!, 0.001f)
    assertEquals(20f, coverage.sourceFrameFallbackPercent!!, 0.001f)
    assertEquals(20f, coverage.staticBlendPercent!!, 0.001f)
    assertEquals(20L, coverage.interframeChangedPixels)
    assertEquals(10f, coverage.interframeChangedFramePercent!!, 0.001f)
    assertEquals(16L, coverage.interframeChangedWarpPixels)
    assertEquals(3L, coverage.interframeChangedSourceFallbackPixels)
    assertEquals(1L, coverage.interframeChangedStaticBlendPixels)
    assertEquals(80f, coverage.interframeChangedWarpPercent!!, 0.001f)
    assertEquals(15f, coverage.interframeChangedSourceFallbackPercent!!, 0.001f)
    assertEquals(5f, coverage.interframeChangedStaticBlendPercent!!, 0.001f)
    assertEquals(60L, coverage.staticVectorProbeSamples)
    assertEquals(20L, coverage.staticVectorLikelyMotionSamples)
    assertEquals(15L, coverage.staticVectorUncertainMotionSamples)
    assertEquals(17L, coverage.staticVectorNearZeroConfidentSamples)
    assertEquals(8L, coverage.staticVectorNearZeroUncertainSamples)
    assertEquals(100f / 3f, coverage.staticVectorLikelyMotionPercent!!, 0.001f)
    assertEquals(25f, coverage.staticVectorUncertainMotionPercent!!, 0.001f)
    assertEquals(28.3333f, coverage.staticVectorNearZeroConfidentPercent!!, 0.001f)
    assertEquals(13.3333f, coverage.staticVectorNearZeroUncertainPercent!!, 0.001f)
  }
}
