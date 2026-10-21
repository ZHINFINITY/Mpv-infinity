/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

/** Pure policy: optical UI must never change decoder/output settings or sample HDR. */
object GlassSamplingPolicy {
  const val MaxSampleEdge = 192
  const val PlayingIntervalMs = 200L
  const val PausedIntervalMs = 1000L
  const val RetryIntervalMs = 1200L
  const val FailureLimit = 3

  fun canSample(enabled: Boolean, visible: Boolean, audioOnly: Boolean, hdr: Boolean,
    lowRam: Boolean, thermalStatus: Int): Boolean =
    enabled && visible && !audioOnly && !hdr && !lowRam && thermalStatus < 3

  fun sampleSize(width: Int, height: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val longest = maxOf(width, height)
    if (longest <= MaxSampleEdge) return width to height
    // Long multiplication avoids overflow; integer scaling keeps the long edge exactly bounded.
    return maxOf(1, (width.toLong() * MaxSampleEdge / longest).toInt()) to
      maxOf(1, (height.toLong() * MaxSampleEdge / longest).toInt())
  }

  fun interval(playing: Boolean, failures: Int): Long =
    if (failures > 0) RetryIntervalMs else if (playing) PlayingIntervalMs else PausedIntervalMs
}
