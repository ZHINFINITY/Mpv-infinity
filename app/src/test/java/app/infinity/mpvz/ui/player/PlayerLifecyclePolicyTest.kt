/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
  fun audioLaunchesSkipNativeMedia3Construction() {
    assertFalse(PlayerLifecyclePolicy.shouldInitializeNativeMedia3Engine(isAudioLaunch = true))
    assertTrue(PlayerLifecyclePolicy.shouldInitializeNativeMedia3Engine(isAudioLaunch = false))
  }

  @Test
  fun composeCoverArtDisablesMpvEmbeddedAudioDisplay() {
    assertEquals("no", PlayerLifecyclePolicy.audioDisplayOption(configOwnsAudioDisplay = false))
    assertNull(PlayerLifecyclePolicy.audioDisplayOption(configOwnsAudioDisplay = true))
  }

  @Test
  fun longBackgroundVideoRebuildsTheRendererInsteadOfLeavingABlackSurface() {
    assertTrue(
      PlayerLifecyclePolicy.shouldRebuildRendererAfterBackground(
        stoppedForMs = 15 * 60 * 1000L,
        isAudioMedia = false,
      ),
    )
    assertFalse(
      PlayerLifecyclePolicy.shouldRebuildRendererAfterBackground(
        stoppedForMs = 5_000L,
        isAudioMedia = false,
      ),
    )
    assertFalse(
      PlayerLifecyclePolicy.shouldRebuildRendererAfterBackground(
        stoppedForMs = 15 * 60 * 1000L,
        isAudioMedia = true,
      ),
    )
  }
}
