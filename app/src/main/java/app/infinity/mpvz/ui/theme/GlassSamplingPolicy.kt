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
    val scale = minOf(1.0, MaxSampleEdge.toDouble() / maxOf(width, height))
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
  }

  fun interval(playing: Boolean, failures: Int): Long =
    if (failures > 0) RetryIntervalMs else if (playing) PlayingIntervalMs else PausedIntervalMs
}
