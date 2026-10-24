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
}
