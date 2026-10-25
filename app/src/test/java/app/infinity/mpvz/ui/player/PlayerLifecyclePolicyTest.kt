/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
  fun pipDoesNotUseBackgroundHandoffResume() {
    assertFalse(
      PlayerLifecyclePolicy.shouldResumeAfterBackgroundHandoff(
        backgroundPlaybackSessionActive = true,
        wasPlayingBeforeHandoff = true,
        isInPictureInPictureMode = true,
      ),
    )
  }

  @Test
  fun inactiveBackgroundSessionDoesNotResume() {
    assertFalse(
      PlayerLifecyclePolicy.shouldResumeAfterBackgroundHandoff(
        backgroundPlaybackSessionActive = false,
        wasPlayingBeforeHandoff = true,
        isInPictureInPictureMode = false,
      ),
    )
  }

  @Test
  fun musicLaunchDoesNotConstructNativeMedia3() {
    assertFalse(PlayerLifecyclePolicy.shouldInitializeNativeMedia3Engine(isAudioLaunch = true))
    assertTrue(PlayerLifecyclePolicy.shouldInitializeNativeMedia3Engine(isAudioLaunch = false))
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

  // Owner report (build 1906): the first minimize after a fresh install stopped the song. Audio
  // background playback defaults to off, and onDestroy only kept the session for that preference,
  // so the Mini Player handoff was torn down and the queue cleared.
  @Test
  fun miniPlayerHandoffKeepsTheSessionEvenWithBackgroundPlaybackOff() {
    assertTrue(
      PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(
        backgroundPlaybackEnabled = false,
        backgroundPlaybackSessionActive = true,
        miniPlayerHandoff = true,
      ),
    )
  }

  @Test
  fun keepAliveStillNeedsAnActiveServiceSession() {
    assertFalse(
      PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(
        backgroundPlaybackEnabled = true,
        backgroundPlaybackSessionActive = false,
        miniPlayerHandoff = true,
      ),
    )
    assertFalse(
      PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(
        backgroundPlaybackEnabled = false,
        backgroundPlaybackSessionActive = true,
        miniPlayerHandoff = false,
      ),
    )
    assertTrue(
      PlayerLifecyclePolicy.shouldKeepBackgroundPlaybackAliveOnDestroy(
        backgroundPlaybackEnabled = true,
        backgroundPlaybackSessionActive = true,
      ),
    )
  }
}
