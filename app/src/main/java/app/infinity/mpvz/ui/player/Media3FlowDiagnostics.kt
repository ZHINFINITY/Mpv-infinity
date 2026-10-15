package app.infinity.mpvz.ui.player

import kotlin.math.ceil

/** Rolling distribution for valid non-zero GPU or CPU durations, represented in microseconds. */
data class FlowDurationSummary(
  val averageUs: Double? = null,
  val p95Us: Double? = null,
  val maximumUs: Double? = null,
  val sampleCount: Int = 0,
)

/** GLES-only elapsed query results. Vulkan compute is reported separately below. */
data class FlowGlesTimingStats(
  val supported: Boolean = false,
  val status: String = "unsupported",
  /** Available, non-disjoint timer results, including zero-duration results. */
  val validResults: Long = 0L,
  val pendingResults: Int = 0,
  val disjointResultsDiscarded: Long = 0L,
  val zeroDurationResults: Long = 0L,
  val skippedBecausePoolFull: Long = 0L,
  /** GLES presentation blit only; this is not a Vulkan compute measurement. */
  val presentationDraw: FlowDurationSummary = FlowDurationSummary(),
)

data class FlowVulkanStageTiming(
  val status: String = "unsupported_context",
  val supported: Boolean = false,
  val dispatches: Long = 0L,
  val completedSamples: Long = 0L,
  /** Libplacebo's query ring may drop old results, so unresolved is pending-or-dropped. */
  val pendingOrDropped: Long = 0L,
  val averageUs: Double? = null,
  val maximumUs: Double? = null,
)

data class FlowVulkanTimingStats(
  val status: String = "unsupported_context",
  val timestampsSupported: Boolean = false,
  val timestampValidBits: Int = 0,
  val timestampPeriodNs: Double = 0.0,
  val timestampComputeAndGraphics: Boolean = false,
  val computeQueueFamily: Int = -1,
  val preprocess: FlowVulkanStageTiming = FlowVulkanStageTiming(),
  val analysis: FlowVulkanStageTiming = FlowVulkanStageTiming(),
  val motionFine: FlowVulkanStageTiming = FlowVulkanStageTiming(),
  val motionMiddle: FlowVulkanStageTiming = FlowVulkanStageTiming(),
  val motionCoarse: FlowVulkanStageTiming = FlowVulkanStageTiming(),
  val motion: FlowVulkanStageTiming = FlowVulkanStageTiming(),
  val synthesis: FlowVulkanStageTiming = FlowVulkanStageTiming(),
)

data class FlowCpuTimingStats(
  /** Time queued before Media3's frame handler is released, in microseconds. */
  val inputQueueWait: FlowDurationSummary = FlowDurationSummary(),
  val frameHandlerCall: FlowDurationSummary = FlowDurationSummary(),
  /** CPU time inside eglSwapBuffers; GPU draw time is reported separately. */
  val eglSwapWait: FlowDurationSummary = FlowDurationSummary(),
  /** CPU time to stage an input and enqueue Vulkan work; not GPU completion time. */
  val vulkanInputPrepare: FlowDurationSummary = FlowDurationSummary(),
  /** CPU time to enqueue synthesis and the output handoff; not GPU completion time. */
  val vulkanSynthesisSubmit: FlowDurationSummary = FlowDurationSummary(),
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
  /** Pixels whose same-coordinate weighted absolute RGB delta reaches the diagnostic threshold. */
  val interframeChangedPixels: Long = 0L,
  val interframeChangedFramePercent: Float? = null,
  val interframeChangedWarpPixels: Long = 0L,
  val interframeChangedSourceFallbackPixels: Long = 0L,
  val interframeChangedStaticBlendPixels: Long = 0L,
  /** Outcome percentages use interframeChangedPixels as their denominator. */
  val interframeChangedWarpPercent: Float? = null,
  val interframeChangedSourceFallbackPercent: Float? = null,
  val interframeChangedStaticBlendPercent: Float? = null,
  /** One vector probe is taken at most per 8x8 output workgroup on sampled frames. */
  val staticVectorProbeSamples: Long = 0L,
  /** Magnitude >0.5 processing pixels and bidirectional reliability >=0.15. */
  val staticVectorLikelyMotionSamples: Long = 0L,
  /** Magnitude >0.5 processing pixels and bidirectional reliability <0.15. */
  val staticVectorUncertainMotionSamples: Long = 0L,
  /** Magnitude <=0.5 processing pixels and bidirectional reliability >=0.15. */
  val staticVectorNearZeroConfidentSamples: Long = 0L,
  /** Magnitude <=0.5 processing pixels and bidirectional reliability <0.15. */
  val staticVectorNearZeroUncertainSamples: Long = 0L,
  val staticVectorLikelyMotionPercent: Float? = null,
  val staticVectorUncertainMotionPercent: Float? = null,
  val staticVectorNearZeroConfidentPercent: Float? = null,
  val staticVectorNearZeroUncertainPercent: Float? = null,
  val skippedSamples: Long = 0L,
  val invalidSamples: Long = 0L,
)

internal data class FlowCoverageSample(
  val motionWarpPixels: Long,
  val sourceFrameFallbackPixels: Long,
  val staticBlendPixels: Long,
  val staticVectorLikelyMotionSamples: Long = 0L,
  val staticVectorUncertainMotionSamples: Long = 0L,
  val staticVectorNearZeroConfidentSamples: Long = 0L,
  val staticVectorNearZeroUncertainSamples: Long = 0L,
  val interframeChangedPixels: Long = 0L,
  val interframeChangedWarpPixels: Long = 0L,
  val interframeChangedSourceFallbackPixels: Long = 0L,
  val interframeChangedStaticBlendPixels: Long = 0L,
)

internal data class FlowOutputDecisionSnapshot(
  val synthesizedCount: Long,
  val passthroughCount: Long,
  val passthroughReasons: Map<String, Long>,
) {
  fun asLogFieldValue(): String {
    val reasons =
      passthroughReasons.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }
        .ifEmpty { "none" }
    return "synthesized:$synthesizedCount,passthrough:$passthroughCount,passthroughReasons:$reasons"
  }
}

/** Cumulative sink-session output decisions; these are cadence/fallback proxies, not pixel quality. */
internal class FlowOutputDecisionCounters {
  private var synthesizedCount = 0L
  private var passthroughCount = 0L
  private val passthroughReasons = linkedMapOf<String, Long>()

  @Synchronized
  fun record(state: String, reason: String) {
    if (state == "synthesized") {
      synthesizedCount++
    } else {
      passthroughCount++
      val key = reason.takeIf { it.isNotBlank() } ?: "unknown"
      passthroughReasons[key] = (passthroughReasons[key] ?: 0L) + 1L
    }
  }

  @Synchronized
  fun snapshot(): FlowOutputDecisionSnapshot =
    FlowOutputDecisionSnapshot(
      synthesizedCount = synthesizedCount,
      passthroughCount = passthroughCount,
      passthroughReasons = passthroughReasons.toMap(),
    )
}

/** Estimates the source-pixel spacing represented by one motion-grid step, not a pixel artifact score. */
internal fun estimateFlowMotionGridStepSourcePx(
  sourceWidth: Int,
  sourceHeight: Int,
  processingWidth: Int,
  processingHeight: Int,
  gridStep: Int = 4,
): Pair<Double, Double>? {
  if (sourceWidth <= 0 || sourceHeight <= 0 || processingWidth <= 0 || processingHeight <= 0 || gridStep <= 0) {
    return null
  }
  return (sourceWidth.toDouble() * gridStep / processingWidth) to
    (sourceHeight.toDouble() * gridStep / processingHeight)
}

internal object Media3FlowDiagnosticMath {
  private const val VULKAN_TIMING_SCHEMA_VERSION = 3L
  private const val VULKAN_TIMING_STAGE_COUNT = 6
  private const val VULKAN_TIMING_HEADER_SIZE = 7
  private const val VULKAN_TIMING_STAGE_SIZE = 5

  fun vulkanTimerStageStatus(
    timestampsSupported: Boolean,
    timerCreated: Boolean,
    dispatches: Long,
    completedSamples: Long,
  ): String = when {
    !timestampsSupported -> "unsupported_timestamp_capability"
    !timerCreated -> "unsupported_libplacebo_timer"
    completedSamples > 0L -> "ready"
    dispatches > 0L -> "pending_or_dropped"
    else -> "supported_waiting_for_dispatch"
  }

  private fun aggregateMotionStages(stages: List<FlowVulkanStageTiming>): FlowVulkanStageTiming {
    val dispatches = stages.sumOf { it.dispatches }
    val completed = stages.sumOf { it.completedSamples }
    val supported = stages.isNotEmpty() && stages.all { it.supported }
    val unsupportedStatus = stages.map { it.status }.distinct().singleOrNull()
    val status = when {
      supported && completed > 0L -> "ready"
      supported && dispatches > 0L -> "pending_or_dropped"
      supported -> "supported_waiting_for_dispatch"
      unsupportedStatus != null -> unsupportedStatus
      else -> "partial_timer_support"
    }
    val weightedTotalUs = stages.sumOf { (it.averageUs ?: 0.0) * it.completedSamples }
    return FlowVulkanStageTiming(
      status = status,
      supported = supported,
      dispatches = dispatches,
      completedSamples = completed,
      pendingOrDropped = stages.sumOf { it.pendingOrDropped },
      averageUs = weightedTotalUs.takeIf { completed > 0L }?.div(completed),
      maximumUs = stages.mapNotNull { it.maximumUs }.maxOrNull(),
    )
  }

  fun parseVulkanTimingSnapshot(values: LongArray?): FlowVulkanTimingStats {
    val expectedSize = VULKAN_TIMING_HEADER_SIZE + VULKAN_TIMING_STAGE_COUNT * VULKAN_TIMING_STAGE_SIZE
    if (values == null) return FlowVulkanTimingStats(status = "snapshot_unavailable")
    if (values.size != expectedSize || values[0] != VULKAN_TIMING_SCHEMA_VERSION ||
      values[6] != VULKAN_TIMING_STAGE_COUNT.toLong()
    ) {
      return FlowVulkanTimingStats(status = "unsupported_snapshot_schema")
    }
    val timestampsSupported = values[1] != 0L
    val timestampPeriodNs = values[2].coerceAtLeast(0L).toDouble() / 1_000.0
    val timestampComputeAndGraphics = values[3] != 0L
    val timestampValidBits = values[4].coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val computeQueueFamily = values[5].takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt() ?: -1
    val capabilityStatus = when {
      timestampPeriodNs <= 0.0 -> "unsupported_timestamp_period"
      !timestampComputeAndGraphics && timestampValidBits <= 0 -> "unsupported_compute_queue_timestamp"
      !timestampsSupported -> "unsupported_timestamp_capability"
      else -> "supported"
    }
    fun stage(index: Int): FlowVulkanStageTiming {
      val offset = VULKAN_TIMING_HEADER_SIZE + index * VULKAN_TIMING_STAGE_SIZE
      val timerCreated = values[offset] != 0L
      val dispatches = values[offset + 1].coerceAtLeast(0L)
      val completed = values[offset + 2].coerceAtLeast(0L)
      val totalNs = values[offset + 3].coerceAtLeast(0L)
      val maximumNs = values[offset + 4].coerceAtLeast(0L)
      val supported = timestampsSupported && timerCreated
      return FlowVulkanStageTiming(
        status = vulkanTimerStageStatus(timestampsSupported, timerCreated, dispatches, completed),
        supported = supported,
        dispatches = dispatches,
        completedSamples = completed,
        pendingOrDropped = if (supported) (dispatches - completed).coerceAtLeast(0L) else 0L,
        averageUs = totalNs.takeIf { completed > 0L }?.toDouble()?.div(completed)?.div(1_000.0),
        maximumUs = maximumNs.takeIf { completed > 0L }?.toDouble()?.div(1_000.0),
      )
    }
    val stages = List(VULKAN_TIMING_STAGE_COUNT, ::stage)
    val state = when {
      capabilityStatus != "supported" -> capabilityStatus
      stages.none { it.supported } -> "unsupported_libplacebo_timer"
      stages.any { it.completedSamples > 0L } -> "ready"
      stages.any { it.dispatches > 0L } -> "pending_or_dropped"
      else -> "supported_waiting_for_dispatch"
    }
    return FlowVulkanTimingStats(
      status = state,
      timestampsSupported = timestampsSupported,
      timestampValidBits = timestampValidBits,
      timestampPeriodNs = timestampPeriodNs,
      timestampComputeAndGraphics = timestampComputeAndGraphics,
      computeQueueFamily = computeQueueFamily,
      preprocess = stages[0],
      analysis = stages[1],
      motionFine = stages[2],
      motionMiddle = stages[3],
      motionCoarse = stages[4],
      motion = aggregateMotionStages(stages.subList(2, 5)),
      synthesis = stages[5],
    )
  }

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
    val interframeChanged = samples.sumOf { it.interframeChangedPixels.coerceAtLeast(0L) }
    val interframeChangedWarp = samples.sumOf { it.interframeChangedWarpPixels.coerceAtLeast(0L) }
    val interframeChangedFallback = samples.sumOf { it.interframeChangedSourceFallbackPixels.coerceAtLeast(0L) }
    val interframeChangedStaticBlend = samples.sumOf { it.interframeChangedStaticBlendPixels.coerceAtLeast(0L) }
    val staticVectorLikelyMotion = samples.sumOf { it.staticVectorLikelyMotionSamples.coerceAtLeast(0L) }
    val staticVectorUncertainMotion = samples.sumOf { it.staticVectorUncertainMotionSamples.coerceAtLeast(0L) }
    val staticVectorNearZeroConfident = samples.sumOf { it.staticVectorNearZeroConfidentSamples.coerceAtLeast(0L) }
    val staticVectorNearZeroUncertain = samples.sumOf { it.staticVectorNearZeroUncertainSamples.coerceAtLeast(0L) }
    val staticVectorProbeSamples = staticVectorLikelyMotion + staticVectorUncertainMotion +
      staticVectorNearZeroConfident + staticVectorNearZeroUncertain
    val total = warped + fallback + staticBlend
    if (total <= 0L) return FlowPixelCoverageStats(samples = samples.size)
    fun percentage(count: Long): Float = (count.toDouble() * 100.0 / total).toFloat()
    fun changedOutcomePercentage(count: Long): Float? = if (interframeChanged > 0L) {
      (count.toDouble() * 100.0 / interframeChanged).toFloat()
    } else {
      null
    }
    fun staticProbePercentage(count: Long): Float? = if (staticVectorProbeSamples > 0L) {
      (count.toDouble() * 100.0 / staticVectorProbeSamples).toFloat()
    } else {
      null
    }
    return FlowPixelCoverageStats(
      samples = samples.size,
      sampledPixels = total,
      motionWarpPixels = warped,
      sourceFrameFallbackPixels = fallback,
      staticBlendPixels = staticBlend,
      motionWarpPercent = percentage(warped),
      sourceFrameFallbackPercent = percentage(fallback),
      staticBlendPercent = percentage(staticBlend),
      interframeChangedPixels = interframeChanged,
      interframeChangedFramePercent = percentage(interframeChanged),
      interframeChangedWarpPixels = interframeChangedWarp,
      interframeChangedSourceFallbackPixels = interframeChangedFallback,
      interframeChangedStaticBlendPixels = interframeChangedStaticBlend,
      interframeChangedWarpPercent = changedOutcomePercentage(interframeChangedWarp),
      interframeChangedSourceFallbackPercent = changedOutcomePercentage(interframeChangedFallback),
      interframeChangedStaticBlendPercent = changedOutcomePercentage(interframeChangedStaticBlend),
      staticVectorProbeSamples = staticVectorProbeSamples,
      staticVectorLikelyMotionSamples = staticVectorLikelyMotion,
      staticVectorUncertainMotionSamples = staticVectorUncertainMotion,
      staticVectorNearZeroConfidentSamples = staticVectorNearZeroConfident,
      staticVectorNearZeroUncertainSamples = staticVectorNearZeroUncertain,
      staticVectorLikelyMotionPercent = staticProbePercentage(staticVectorLikelyMotion),
      staticVectorUncertainMotionPercent = staticProbePercentage(staticVectorUncertainMotion),
      staticVectorNearZeroConfidentPercent = staticProbePercentage(staticVectorNearZeroConfident),
      staticVectorNearZeroUncertainPercent = staticProbePercentage(staticVectorNearZeroUncertain),
    )
  }
}
