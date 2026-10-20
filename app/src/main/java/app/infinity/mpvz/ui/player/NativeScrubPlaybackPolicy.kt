/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * Coordinates the pause/resume side effects around a native seekbar drag.
 *
 * A drag can produce many position callbacks, so the original playing state is
 * captured only on the first callback. Releasing or cancelling the drag clears
 * the capture and resumes only when playback was playing before the drag.
 */
internal class NativeScrubPlaybackPolicy {
  private var wasPlaying: Boolean? = null

  fun begin(isPlaying: Boolean): Boolean {
    if (wasPlaying != null) return false
    wasPlaying = isPlaying
    return isPlaying
  }

  fun end(): Boolean {
    val shouldResume = wasPlaying == true
    wasPlaying = null
    return shouldResume
  }

  fun cancel(): Boolean = end()

  fun isActive(): Boolean = wasPlaying != null
}
