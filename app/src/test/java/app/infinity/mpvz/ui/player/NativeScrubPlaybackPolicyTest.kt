/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeScrubPlaybackPolicyTest {
  @Test
  fun playingPlaybackPausesOnceAndResumesOnRelease() {
    val policy = NativeScrubPlaybackPolicy()

    assertTrue(policy.begin(isPlaying = true))
    assertFalse("additional drag positions must not pause again", policy.begin(isPlaying = true))
    assertTrue(policy.end())
    assertFalse("release is idempotent", policy.end())
  }

  @Test
  fun pausedPlaybackRemainsPausedAfterRelease() {
    val policy = NativeScrubPlaybackPolicy()

    assertFalse(policy.begin(isPlaying = false))
    assertFalse(policy.end())
  }

  @Test
  fun cancellationUsesTheSameRestorationPathAndAllowsTheNextDrag() {
    val policy = NativeScrubPlaybackPolicy()

    assertTrue(policy.begin(isPlaying = true))
    assertTrue(policy.cancel())
    assertFalse("a later paused drag needs no resume", policy.begin(isPlaying = false))
    assertFalse(policy.end())
  }
}
