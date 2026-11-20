/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

/** Pure policy: optical UI must never change decoder/output settings or sample HDR. */
object GlassSamplingPolicy {
  const val MaxSampleEdge = 192
  /**
   * SurfaceView samples use PixelCopy and an off-thread frost pass, not the reference app's shared
   * TextureView GPU layer. Keep sheet capture to 15 fps; controls-only samples run at 10 fps.
   */
  const val PlayingIntervalMs = 66L
  const val WarmPlayingIntervalMs = 120L
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

  /** Controls-only cadence: small buttons over the video do not need display rate. */
  const val ControlsPlayingIntervalMs = 100L
  /** Gaussian sigma equivalent of n separable box passes of radius r. */
  fun frostSigma(radius: Int = GlassFrostPolicy.Radius, passes: Int = GlassFrostPolicy.Passes): Float {
    require(radius >= 0 && passes >= 0)
    return kotlin.math.sqrt(passes * (((2 * radius + 1) * (2 * radius + 1) - 1) / 12f))
  }
  /** Cover the 350 ms sheet animation plus a small margin so captures do not compete with it. */
  const val TransitionSettleMs = 450L
  const val ShaderWarmupDelayMs = 600L

  fun interval(playing: Boolean, failures: Int, thermalStatus: Int = 0, largeSurface: Boolean = true): Long = when {
    failures > 0 -> RetryIntervalMs
    !playing -> PausedIntervalMs
    thermalStatus >= 2 -> WarmPlayingIntervalMs // PowerManager.THERMAL_STATUS_MODERATE
    !largeSurface -> ControlsPlayingIntervalMs
    else -> PlayingIntervalMs
  }

  /**
   * Remaining pause while a sheet animates. Only applies when a frame already exists:
   * the very first sample is never delayed, so glass never opens without a scene.
   */
  fun settleDelay(now: Long, settleUntil: Long, hasFrame: Boolean): Long =
    if (!hasFrame) 0L else (settleUntil - now).coerceAtLeast(0L)

  /** Delay until the next copy, subtracting the time the copy and frost already took. */
  fun remainingDelay(interval: Long, elapsedMs: Long): Long =
    (interval - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(if (interval < PausedIntervalMs) 1L else 0L)
}
