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
  fun coverageReportsWarpFallbackAndStaticBlendCountsAndPercentages() {
    val coverage = Media3FlowDiagnosticMath.summarizeCoverage(
      listOf(
        FlowCoverageSample(motionWarpPixels = 80L, sourceFrameFallbackPixels = 20L, staticBlendPixels = 0L),
        FlowCoverageSample(motionWarpPixels = 40L, sourceFrameFallbackPixels = 20L, staticBlendPixels = 40L),
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
  }
}
