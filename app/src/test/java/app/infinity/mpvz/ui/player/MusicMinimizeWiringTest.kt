/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import app.infinity.mpvz.ui.browser.components.MiniPlayerAnchorProbePolicy
import app.infinity.mpvz.ui.player.controls.MusicMinimizeTransitionPolicy
import app.infinity.mpvz.ui.player.controls.MusicMinimizeTransitionPolicy.RootBackgroundAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the Activity/browser wiring behind the build-1906 music minimize fixes. */
class MusicMinimizeWiringTest {
  private fun source(relative: String): String {
    val root = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    return File(root, relative).readText()
  }
  private val activity by lazy { source("java/app/infinity/mpvz/ui/player/PlayerActivity.kt") }

  private fun body(text: String, signature: String): String {
    val start = text.indexOf(signature)
    assertTrue("missing $signature", start >= 0)
    val end = text.indexOf("\n  }\n", start)
    assertTrue("unterminated $signature", end > start)
    return text.substring(start, end)
  }

  // Black above the page on the first minimize: the layout root paints black for video, so
  // window translucency alone never showed the library.
  @Test
  fun translucencyAlsoClearsTheOpaquePlayerRoot() {
    assertTrue(
      "player_layout root is opaque black; the clear below is required",
      source("res/layout/player_layout.xml").contains("android:background=\"@android:color/black\""),
    )
    val translucent = body(activity, "fun setAudioTransitionTranslucent(")
    assertTrue(translucent.contains("setPlayerRootSeeThrough(enabled && success)"))
    val root = body(activity, "private fun setPlayerRootSeeThrough(")
    assertTrue(root.contains("root.background = null"))
    assertTrue(root.contains("root.background = audioRootBackground"))
  }

  @Test
  fun rootBackgroundIsClearedOnceAndRestoredOnce() {
    assertEquals(RootBackgroundAction.Clear, MusicMinimizeTransitionPolicy.rootBackgroundAction(true, false))
    assertEquals(RootBackgroundAction.Keep, MusicMinimizeTransitionPolicy.rootBackgroundAction(true, true))
    assertEquals(RootBackgroundAction.Restore, MusicMinimizeTransitionPolicy.rootBackgroundAction(false, true))
    assertEquals(RootBackgroundAction.Keep, MusicMinimizeTransitionPolicy.rootBackgroundAction(false, false))
  }

  // First minimize slid instead of morphing: no mini player had ever been laid out, so there
  // was no landing rect. The probe publishes one wherever the real mini player would sit.
  @Test
  fun landingRectExistsBeforeTheFirstMiniPlayer() {
    assertTrue(MiniPlayerAnchorProbePolicy.shouldProbe(isMediaActive = false, isSettingsScreen = false))
    assertFalse(MiniPlayerAnchorProbePolicy.shouldProbe(isMediaActive = true, isSettingsScreen = false))
    assertFalse(MiniPlayerAnchorProbePolicy.shouldProbe(isMediaActive = false, isSettingsScreen = true))
    val mini = source("java/app/infinity/mpvz/ui/browser/components/MiniPlayer.kt")
    assertTrue(mini.contains("MiniPlayerAnchorProbe(modifier)"))
    val probe = body(mini, "internal fun MiniPlayerAnchorProbe(")
    assertTrue(probe.contains("MiniPlayerTransitionAnchor.publish("))
  }

  // Music stopped on the first minimize with background playback off (the default).
  @Test
  fun miniPlayerHandoffReachesTheDestroyDecision() {
    val back = body(activity, "private fun handleBackPress(")
    assertTrue(back.contains("miniPlayerHandoffRequested = miniPlayerHandoff"))
    assertTrue(activity.contains("miniPlayerHandoff = (playbackWasInitialized || nativeWasActive) && miniPlayerHandoffRequested"))
    val await = body(activity, "private fun awaitServiceMediaSessionOwnership(")
    assertTrue("a failed handoff must not keep the session", await.contains("miniPlayerHandoffRequested = false"))
  }

  // "Could not load this link": never assign an Intent's type after its data.
  @Test
  fun noIntentAssignsTypeRightAfterData() {
    val bad = Regex("""data = Uri\.parse\([^\n]*\)\n\s*(if \([^\n]*\) )?type = """)
    val players = listOf(
      "java/app/infinity/mpvz/ui/player/PlayerActivity.kt",
      "java/app/infinity/mpvz/ui/player/MediaPlaybackService.kt",
      "java/app/infinity/mpvz/ui/browser/components/MiniPlayer.kt",
      "java/app/infinity/mpvz/ui/browser/components/MiniPlayerResume.kt",
    )
    for (path in players) {
      assertFalse("$path sets type after data (setType clears data)", bad.containsMatchIn(source(path)))
    }
    assertTrue(body(activity, "private fun restoreAudioPlaybackSessionIfNeeded(").contains("AudioResumeLaunch.applyTo("))
  }
}
