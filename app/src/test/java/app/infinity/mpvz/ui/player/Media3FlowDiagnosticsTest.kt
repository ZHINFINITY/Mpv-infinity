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
      "unsupported_timestamp_capability",
      Media3FlowDiagnosticMath.vulkanTimerStageStatus(false, true, 3L, 0L),
    )
    assertEquals(
      "pending_or_dropped",
      Media3FlowDiagnosticMath.vulkanTimerStageStatus(true, true, 2L, 0L),
    )
    assertEquals(
      "ready",
      Media3FlowDiagnosticMath.vulkanTimerStageStatus(true, true, 2L, 1L),
    )
    assertEquals(
      "unsupported_libplacebo_timer",
      Media3FlowDiagnosticMath.vulkanTimerStageStatus(true, false, 0L, 0L),
    )
  }

  @Test
  fun vulkanSnapshotReportsStageAveragesAndUnresolvedQueriesWithoutCallingThemExactPending() {
    val snapshot = LongArray(37).apply {
      this[0] = 3L
      this[1] = 1L
      this[2] = 1_000L // 1 ns timestamp period, represented as picoseconds
      this[3] = 0L
      this[4] = 32L
      this[5] = 2L
      this[6] = 6L
      for (stage in 0 until 6) {
        val offset = 7 + stage * 5
        this[offset] = 1L
      }
      val preprocessOffset = 7
      this[preprocessOffset + 1] = 2L
      this[preprocessOffset + 2] = 2L
      this[preprocessOffset + 3] = 1_000_000L
      this[preprocessOffset + 4] = 900_000L
      val motionOffset = 7 + 2 * 5
      this[motionOffset + 1] = 3L
      this[motionOffset + 2] = 0L
      val middleOffset = 7 + 3 * 5
      this[middleOffset + 1] = 1L
      this[middleOffset + 2] = 1L
      this[middleOffset + 3] = 1_500_000L
    }

    val timings = Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(snapshot)

    assertEquals("ready", timings.status)
    assertEquals(true, timings.timestampsSupported)
    assertEquals(32, timings.timestampValidBits)
    assertEquals(1.0, timings.timestampPeriodNs, 0.0001)
    assertEquals(false, timings.timestampComputeAndGraphics)
    assertEquals(2, timings.computeQueueFamily)
    assertEquals("ready", timings.preprocess.status)
    assertEquals(2L, timings.preprocess.completedSamples)
    assertEquals(500.0, timings.preprocess.averageUs!!, 0.001)
    assertEquals(900.0, timings.preprocess.maximumUs!!, 0.001)
    assertEquals("pending_or_dropped", timings.motionFine.status)
    assertEquals(3L, timings.motionFine.pendingOrDropped)
    assertEquals("ready", timings.motion.status)
    assertEquals(3L, timings.motion.pendingOrDropped)
    assertEquals(1L, timings.motion.completedSamples)
    assertEquals(1_500.0, timings.motion.averageUs!!, 0.001)
  }

  @Test
  fun vulkanSnapshotUsesComputeAndGraphicsSupportEvenWhenQueueValidBitsAreZero() {
    val supportedByDeviceLimits = LongArray(37).apply {
      this[0] = 3L
      this[1] = 1L
      this[2] = 1_000L
      this[3] = 1L
      this[4] = 0L
      this[5] = 2L
      this[6] = 6L
      for (stage in 0 until 6) this[7 + stage * 5] = 1L
    }
    assertEquals(
      "supported_waiting_for_dispatch",
      Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(supportedByDeviceLimits).status,
    )
    assertEquals(
      0,
      Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(supportedByDeviceLimits).timestampValidBits,
    )

    val unsupportedQueue = supportedByDeviceLimits.copyOf().apply {
      this[1] = 0L
      this[3] = 0L
    }
    assertEquals(
      "unsupported_compute_queue_timestamp",
      Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(unsupportedQueue).status,
    )
    val unsupportedPeriod = supportedByDeviceLimits.copyOf().apply {
      this[2] = 0L
      this[4] = 48L
      this[3] = 1L
    }
    assertEquals(
      "unsupported_timestamp_period",
      Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(unsupportedPeriod).status,
    )
    assertEquals(
      "unsupported_snapshot_schema",
      Media3FlowDiagnosticMath.parseVulkanTimingSnapshot(longArrayOf(3L)).status,
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

  @Test
  fun outputDecisionCountersSeparateSynthesisFromEachPassthroughReason() {
    val counters = FlowOutputDecisionCounters()
    counters.record("synthesized", "motion_pair_ready")
    counters.record("passthrough", "source_frame_due")
    counters.record("passthrough", "motion_not_ready_or_deadline")

    val snapshot = counters.snapshot()

    assertEquals(1L, snapshot.synthesizedCount)
    assertEquals(2L, snapshot.passthroughCount)
    assertEquals(
      mapOf("source_frame_due" to 1L, "motion_not_ready_or_deadline" to 1L),
      snapshot.passthroughReasons,
    )
    assertEquals(
      "synthesized:1,passthrough:2,passthroughReasons:motion_not_ready_or_deadline:1,source_frame_due:1",
      snapshot.asLogFieldValue(),
    )
  }

  @Test
  fun motionPairAnalysisCountersSeparateSwapAndSlotDeferralsFromNativeOutcomes() {
    val counters = FlowMotionPairAnalysisCounters()
    counters.recordDeferredByEglSwap()
    counters.recordDeferredNoReusableSlot()
    counters.recordNoRetainedCandidate()
    counters.recordAttempt()
    counters.recordReady()
    counters.recordAttempt()
    counters.recordFailure()

    val snapshot = counters.snapshot()

    assertEquals(2L, snapshot.attempts)
    assertEquals(1L, snapshot.ready)
    assertEquals(1L, snapshot.failures)
    assertEquals(1L, snapshot.deferredByEglSwap)
    assertEquals(1L, snapshot.deferredNoReusableSlot)
    assertEquals(1L, snapshot.noRetainedCandidate)
    assertEquals(
      "attempts:2,ready:1,failures:1,deferredEglSwap:1,deferredNoSlot:1,noRetainedCandidate:1",
      snapshot.asLogFieldValue(),
    )
  }

  @Test
  fun motionGridSpacingProxyShowsTheApproximateSourcePixelSpanOn4kInput() {
    val spacing = estimateFlowMotionGridStepSourcePx(3840, 2160, 416, 234)

    assertEquals(36.9231, spacing!!.first, 0.001)
    assertEquals(36.9231, spacing.second, 0.001)
    assertEquals(null, estimateFlowMotionGridStepSourcePx(3840, 2160, 0, 234))
  }
}
