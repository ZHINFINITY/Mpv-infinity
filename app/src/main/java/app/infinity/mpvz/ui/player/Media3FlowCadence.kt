package app.infinity.mpvz.ui.player

import java.util.ArrayDeque
import kotlin.math.floor
import kotlin.math.roundToLong

/** Pure timestamp math shared by the Media3 sink and its host-side regression tests. */
internal object Media3FlowCadence {
  private const val MICROS_PER_SECOND = 1_000_000.0

  /** Returns null rather than allowing signed timestamp arithmetic to wrap. */
  fun checkedAdd(a: Long, b: Long): Long? {
    val result = a + b
    return if (((a xor result) and (b xor result)) < 0L) null else result
  }

  /** Returns null rather than allowing signed timestamp arithmetic to wrap. */
  fun checkedSubtract(a: Long, b: Long): Long? {
    val result = a - b
    return if (((a xor b) and (a xor result)) < 0L) null else result
  }

  /** Returns the interpolation fraction for an output PTS strictly between two source PTS values. */
  fun interpolationAlpha(source0Us: Long, source1Us: Long, outputUs: Long): Float? {
    val durationUs = checkedSubtract(source1Us, source0Us) ?: return null
    if (durationUs <= 0L || outputUs <= source0Us || outputUs >= source1Us) return null
    val elapsedUs = checkedSubtract(outputUs, source0Us) ?: return null
    return (elapsedUs.toDouble() / durationUs.toDouble())
      .toFloat()
      .takeIf { it > 0f && it < 1f }
  }

  /**
   * Maps a playback position to the newest target-cadence tick that is due. The speed argument is
   * playback speed (media seconds per wall second), so a 2x stream uses twice the media-time step
   * between display ticks while retaining the requested wall-clock output cadence.
   */
  fun outputTick(positionUs: Long, originUs: Long, targetFps: Int, speed: Float): Long? {
    if (targetFps <= 0 || !speed.isFinite() || speed <= 0f) return null
    val elapsedUs = checkedSubtract(positionUs, originUs) ?: return null
    if (elapsedUs < 0L) return null
    val ticks = elapsedUs.toDouble() * targetFps / (MICROS_PER_SECOND * speed.toDouble())
    if (!ticks.isFinite() || ticks < 0.0) return null
    return floor(ticks).toLong()
  }

  /** Selects the latest due tick when work is behind, otherwise the next tick after one already rendered. */
  fun nextRenderableOutputTick(dueTick: Long, lastOutputTick: Long): Long? {
    if (dueTick < 0L) return null
    return if (dueTick > lastOutputTick) dueTick else checkedAdd(lastOutputTick, 1L)
  }

  /** Returns the target media PTS for an ordinal output tick without cumulative rounding drift. */
  fun outputTimestampUs(tick: Long, originUs: Long, targetFps: Int, speed: Float): Long? {
    if (tick < 0L || targetFps <= 0 || !speed.isFinite() || speed <= 0f) return null
    val offsetUs = tick.toDouble() * MICROS_PER_SECOND * speed.toDouble() / targetFps
    if (!offsetUs.isFinite() || offsetUs < 0.0 || offsetUs >= Long.MAX_VALUE.toDouble()) return null
    val roundedOffsetUs = offsetUs.roundToLong()
    return checkedAdd(originUs, roundedOffsetUs)
  }

  /** Estimates source FPS from the median positive presentation-time delta. */
  fun estimateSourceFps(presentationTimesUs: List<Long>): Float {
    if (presentationTimesUs.size < 2) return 0f
    val deltas = presentationTimesUs.zipWithNext { a, b -> checkedSubtract(b, a) }
      .filterNotNull()
      .filter { it in 1L..10_000_000L }
      .sorted()
    if (deltas.isEmpty()) return 0f
    val middle = deltas.size / 2
    val medianUs = if (deltas.size % 2 == 0) {
      (deltas[middle - 1].toDouble() + deltas[middle].toDouble()) / 2.0
    } else {
      deltas[middle].toDouble()
    }
    return (MICROS_PER_SECOND / medianUs).toFloat()
      .takeIf { it.isFinite() && it > 0f }
      ?: 0f
  }

  /** True when the source has fewer frames per wall second than the requested output cadence. */
  fun needsInterpolation(sourceDeltaUs: Long, targetFps: Int, speed: Float): Boolean {
    if (sourceDeltaUs <= 0L || targetFps <= 0 || !speed.isFinite() || speed <= 0f) return false
    val sourceWallDeltaUs = sourceDeltaUs.toDouble() / speed.toDouble()
    return sourceWallDeltaUs > MICROS_PER_SECOND / targetFps
  }

  /** Number of target ticks skipped when advancing from [previousTick] to [nextTick]. */
  fun skippedTicks(previousTick: Long, nextTick: Long): Long {
    if (previousTick < 0L || nextTick <= previousTick) return 0L
    val tickDistance = checkedSubtract(nextTick, previousTick) ?: return 0L
    return (tickDistance - 1L).coerceAtLeast(0L)
  }

  /**
   * A blocked display callback already has a newer Choreographer callback scheduled. Re-rendering
   * immediately would submit an extra frame outside that cadence; preserve immediate retries only
   * for coalesced one-shot requests that have no display callback to deliver them.
   */
  fun shouldImmediatelyResubmitCoalescedRender(
    taskRequestVersion: Long,
    latestRequestVersion: Long,
    latestRequestIsOneShot: Boolean,
  ): Boolean = latestRequestVersion != taskRequestVersion && latestRequestIsOneShot

  /** Admission proxy only: submit time is CPU enqueue time, not GPU completion time. */
  fun motionAdmissionProxyMs(submitMs: Float, pairGpuMs: Float?): Float =
    maxOf(submitMs, pairGpuMs ?: 0f)

  /** Keeps a decoder frame queued until the sink can retain it in an owned GPU frame slot. */
  fun canReleaseNextDecoderFrame(
    storedFrameCount: Int,
    maxStoredFrames: Int,
    inputInFlight: Boolean,
  ): Boolean =
    !inputInFlight && maxStoredFrames > 0 && storedFrameCount in 0 until maxStoredFrames

  /**
   * Admits only frames within a bounded lookahead and releases them now to the private capture
   * SurfaceTexture. This is not presentation: the window output remains scheduled from playback PTS.
   */
  fun inputReleaseTimeNs(
    framePtsUs: Long,
    clockPositionUs: Long,
    nowNs: Long,
    speed: Float,
    maxLookAheadUs: Long,
  ): Long? {
    if (nowNs < 0L || maxLookAheadUs < 0L || !speed.isFinite() || speed <= 0f) return null
    val mediaDeltaUs = checkedSubtract(framePtsUs, clockPositionUs) ?: return null
    val wallDeltaUs = mediaDeltaUs.toDouble() / speed.toDouble()
    if (
      !wallDeltaUs.isFinite() ||
        wallDeltaUs.coerceAtLeast(0.0) > maxLookAheadUs.toDouble()
    ) return null
    return nowNs
  }

  /** Returns the newest source-frame index that is not ahead of [targetPtsUs], or null if none is due. */
  fun sourceFrameIndexAtOrBefore(framePtsUs: LongArray, targetPtsUs: Long): Int? {
    for (index in framePtsUs.lastIndex downTo 0) {
      if (framePtsUs[index] <= targetPtsUs) return index
    }
    return null
  }
}

internal data class Media3FlowSourcePair(val frame0PtsUs: Long, val frame1PtsUs: Long)

/** Bounded retry state for source pairs whose motion analysis was deferred by present backpressure. */
internal class Media3FlowDeferredPairQueue(private val capacity: Int) {
  private val pairs = ArrayDeque<Media3FlowSourcePair>()

  init {
    require(capacity > 0)
  }

  val size: Int get() = pairs.size

  /** Keeps the newest bounded set of strictly increasing source pairs, without duplicates. */
  fun enqueue(frame0PtsUs: Long, frame1PtsUs: Long) {
    if (frame1PtsUs <= frame0PtsUs) return
    val pair = Media3FlowSourcePair(frame0PtsUs, frame1PtsUs)
    if (pairs.contains(pair)) return
    if (pairs.size == capacity) pairs.removeFirst()
    pairs.addLast(pair)
  }

  /**
   * Drops expired/non-adjacent pairs, then consumes the oldest retained pair only when analysis is allowed.
   */
  fun takeNextRetainedAdjacent(
    retainedFramePtsUs: LongArray,
    retentionBoundaryUs: Long,
    analysisAllowed: Boolean,
    preferredPair: Media3FlowSourcePair? = null,
  ): Media3FlowSourcePair? {
    val iterator = pairs.iterator()
    while (iterator.hasNext()) {
      val pair = iterator.next()
      val stillAdjacent = (0 until retainedFramePtsUs.lastIndex).any { index ->
        retainedFramePtsUs[index] == pair.frame0PtsUs &&
          retainedFramePtsUs[index + 1] == pair.frame1PtsUs
      }
      if (!stillAdjacent || retentionBoundaryUs >= pair.frame1PtsUs) iterator.remove()
    }
    if (!analysisAllowed) return null
    if (preferredPair != null) {
      val iterator = pairs.iterator()
      while (iterator.hasNext()) {
        val pair = iterator.next()
        if (pair == preferredPair) {
          iterator.remove()
          return pair
        }
      }
    }
    return pairs.pollFirst()
  }

  fun clear() = pairs.clear()
}
