/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLifecyclePolicyTest {
  @Test
  fun backgroundHandoffResumesPlayingSession() {
    assertTrue(
      PlayerLifecyclePolicy.shouldResumeAfterBackgroundHandoff(
        backgroundPlaybackSessionActive = true,
        wasPlayingBeforeHandoff = true,
        isInPictureInPictureMode = false,
      ),
    )
  }

  @Test
  fun backgroundHandoffPreservesPausedSession() {
    assertFalse(
      PlayerLifecyclePolicy.shouldResumeAfterBackgroundHandoff(
        backgroundPlaybackSessionActive = true,
        wasPlayingBeforeHandoff = false,
        isInPictureInPictureMode = false,
      ),
    )
  }

  @Test
  fun longBackgroundRebuildWaitsFifteenMinutesAndSkipsMusic() {
    val fifteen = PlayerLifecyclePolicy.LONG_BACKGROUND_REBUILD_THRESHOLD_MS
    assertEquals(900_000L, fifteen)
    assertFalse(PlayerLifecyclePolicy.shouldRebuildRendererAfterBackground(fifteen - 1, isAudioMedia = false))
    assertTrue(PlayerLifecyclePolicy.shouldRebuildRendererAfterBackground(fifteen, isAudioMedia = false))
    assertFalse(PlayerLifecyclePolicy.shouldRebuildRendererAfterBackground(fifteen, isAudioMedia = true))
    assertTrue(PlayerLifecyclePolicy.shouldWaitForSurfaceBeforeRebuild(surfaceReady = false))
    assertFalse(PlayerLifecyclePolicy.shouldWaitForSurfaceBeforeRebuild(surfaceReady = true))
  }

  @Test
  fun videoBackgroundCapturesResumePositionAndSkipsMusic() {
    assertTrue(PlayerLifecyclePolicy.shouldCaptureForegroundResumePosition(isAudioMedia = false))
    assertFalse(PlayerLifecyclePolicy.shouldCaptureForegroundResumePosition(isAudioMedia = true))
    assertEquals(12.5, PlayerLifecyclePolicy.resumePositionSeconds(12.5))
    assertEquals(null, PlayerLifecyclePolicy.resumePositionSeconds(0.0))
    assertEquals(null, PlayerLifecyclePolicy.resumePositionSeconds(Double.NaN))
  }
}
