package app.infinity.mpvz.ui.player

import kotlin.math.floor
import kotlin.math.roundToLong

/** Pure timestamp math shared by the Media3 sink and its host-side regression tests. */
internal object Media3FlowCadence {
  private const val MICROS_PER_SECOND = 1_000_000.0

  /** Returns the interpolation fraction for an output PTS strictly between two source PTS values. */
  fun interpolationAlpha(source0Us: Long, source1Us: Long, outputUs: Long): Float? {
    val durationUs = source1Us - source0Us
    if (durationUs <= 0L || outputUs <= source0Us || outputUs >= source1Us) return null
    return ((outputUs - source0Us).toDouble() / durationUs.toDouble())
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
    val elapsedUs = positionUs - originUs
    if (elapsedUs < 0L) return null
    val ticks = elapsedUs.toDouble() * targetFps / (MICROS_PER_SECOND * speed.toDouble())
    if (!ticks.isFinite() || ticks < 0.0) return null
    return floor(ticks).toLong()
  }

  /** Returns the target media PTS for an ordinal output tick without cumulative rounding drift. */
  fun outputTimestampUs(tick: Long, originUs: Long, targetFps: Int, speed: Float): Long? {
    if (tick < 0L || targetFps <= 0 || !speed.isFinite() || speed <= 0f) return null
    val offsetUs = tick.toDouble() * MICROS_PER_SECOND * speed.toDouble() / targetFps
    if (!offsetUs.isFinite() || offsetUs > Long.MAX_VALUE.toDouble()) return null
    return originUs + offsetUs.roundToLong()
  }

  /** Estimates source FPS from the median positive presentation-time delta. */
  fun estimateSourceFps(presentationTimesUs: List<Long>): Float {
    if (presentationTimesUs.size < 2) return 0f
    val deltas = presentationTimesUs.zipWithNext { a, b -> b - a }
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
  fun skippedTicks(previousTick: Long, nextTick: Long): Long =
    if (previousTick < 0L || nextTick <= previousTick + 1L) 0L else nextTick - previousTick - 1L
}
