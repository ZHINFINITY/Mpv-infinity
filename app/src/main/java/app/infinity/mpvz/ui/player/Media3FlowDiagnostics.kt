package app.infinity.mpvz.ui.player

import kotlin.math.ceil

/** Rolling distribution for valid non-zero GPU or CPU durations, represented in microseconds. */
data class FlowDurationSummary(
  val averageUs: Double? = null,
  val p95Us: Double? = null,
  val maximumUs: Double? = null,
  val sampleCount: Int = 0,
)

data class FlowGpuTimingStats(
  val supported: Boolean = false,
  val status: String = "unsupported",
  /** Available, non-disjoint timer results, including zero-duration results. */
  val validResults: Long = 0L,
  val pendingResults: Int = 0,
  val disjointResultsDiscarded: Long = 0L,
  val zeroDurationResults: Long = 0L,
  val skippedBecausePoolFull: Long = 0L,
  val downsample: FlowDurationSummary = FlowDurationSummary(),
  val motionForward: FlowDurationSummary = FlowDurationSummary(),
  val motionBackward: FlowDurationSummary = FlowDurationSummary(),
  /** Cycle-consistency checks and warp/synthesis share one compute dispatch today. */
  val consistencyAndWarp: FlowDurationSummary = FlowDurationSummary(),
  val presentationDraw: FlowDurationSummary = FlowDurationSummary(),
)

data class FlowCpuTimingStats(
  /** Time queued before Media3's frame handler is released, in microseconds. */
  val inputQueueWait: FlowDurationSummary = FlowDurationSummary(),
  val frameHandlerCall: FlowDurationSummary = FlowDurationSummary(),
  /** CPU time inside eglSwapBuffers; GPU draw time is reported separately. */
  val eglSwapWait: FlowDurationSummary = FlowDurationSummary(),
)

data class FlowPixelCoverageStats(
  val samples: Int = 0,
  val sampledPixels: Long = 0L,
  val motionWarpPixels: Long = 0L,
  val sourceFrameFallbackPixels: Long = 0L,
  /** Pixels on the static-change path use a temporal blend without motion vectors. */
  val staticBlendPixels: Long = 0L,
  val motionWarpPercent: Float? = null,
  val sourceFrameFallbackPercent: Float? = null,
  val staticBlendPercent: Float? = null,
  val skippedSamples: Long = 0L,
  val invalidSamples: Long = 0L,
)

internal data class FlowCoverageSample(
  val motionWarpPixels: Long,
  val sourceFrameFallbackPixels: Long,
  val staticBlendPixels: Long,
)

internal object Media3FlowDiagnosticMath {
  fun summarizeNanoseconds(values: List<Long>): FlowDurationSummary {
    val sorted = values.filter { it > 0L }.sorted()
    if (sorted.isEmpty()) return FlowDurationSummary()
    val averageUs = sorted.sumOf { it.toDouble() } / sorted.size / 1_000.0
    val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
    return FlowDurationSummary(
      averageUs = averageUs,
      p95Us = sorted[p95Index] / 1_000.0,
      maximumUs = sorted.last() / 1_000.0,
      sampleCount = sorted.size,
    )
  }

  fun summarizeCoverage(samples: List<FlowCoverageSample>): FlowPixelCoverageStats {
    if (samples.isEmpty()) return FlowPixelCoverageStats()
    val warped = samples.sumOf { it.motionWarpPixels.coerceAtLeast(0L) }
    val fallback = samples.sumOf { it.sourceFrameFallbackPixels.coerceAtLeast(0L) }
    val staticBlend = samples.sumOf { it.staticBlendPixels.coerceAtLeast(0L) }
    val total = warped + fallback + staticBlend
    if (total <= 0L) return FlowPixelCoverageStats(samples = samples.size)
    fun percentage(count: Long): Float = (count.toDouble() * 100.0 / total).toFloat()
    return FlowPixelCoverageStats(
      samples = samples.size,
      sampledPixels = total,
      motionWarpPixels = warped,
      sourceFrameFallbackPixels = fallback,
      staticBlendPixels = staticBlend,
      motionWarpPercent = percentage(warped),
      sourceFrameFallbackPercent = percentage(fallback),
      staticBlendPercent = percentage(staticBlend),
    )
  }
}
