/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Build 1909 regression: after a background round trip the full music player is the in-app
 * sheet over a service-owned session, so its background-play button has no PlayerActivity and
 * only writes the preference. Nothing applied "off" when the app was left, and the music kept
 * playing in the background until the app was restarted. These pins keep the app-leave path
 * that applies the setting to the current session. The decision itself is covered by
 * [PlayerLifecyclePolicyTest].
 */
class DetachedPlaybackBackgroundSettingWiringTest {
  private val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")

  private fun source(path: String): String = File(root, path).readText()

  private fun body(
    text: String,
    signature: String,
  ): String {
    val start = text.indexOf(signature)
    assertTrue("missing $signature", start >= 0)
    val end = text.indexOf("\n  }\n", start)
    assertTrue("unterminated $signature", end > start)
    return text.substring(start, end)
  }

  @Test
  fun appAppliesBackgroundSettingToServiceOwnedPlaybackWhenLastActivityStops() {
    val stopped = body(source("app/infinity/mpvz/App.kt"), "override fun onActivityStopped(activity: Activity)")
    val guard = stopped.indexOf("startedActivityCount == 0 && !activity.isChangingConfigurations")
    val hook = stopped.indexOf("MediaPlaybackService.onAppBackgrounded()")
    assertTrue("app-leave guard missing", guard >= 0)
    assertTrue("service-owned playback must be handled when the app is left", hook > guard)
  }

  @Test
  fun servicePausesOnlyThroughThePolicyUsingTheMatchingPreference() {
    val service = source("app/infinity/mpvz/ui/player/MediaPlaybackService.kt")
    assertTrue(
      service.contains(
        "internal fun onAppBackgrounded(): Boolean = activeInstance?.pauseDetachedPlaybackIfBackgroundDisabled() == true",
      ),
    )
    val pause = body(service, "private fun pauseDetachedPlaybackIfBackgroundDisabled(): Boolean")
    for (needle in listOf(
      "PlayerLifecyclePolicy.shouldPauseDetachedPlaybackOnAppBackground(",
      "!activityForeground && !handingBackToActivity",
      "isAudio = usesAudioBackgroundPlayback",
      "audioBackgroundPlaybackEnabled = audioPreferences.audioBackgroundPlayback.get()",
      "videoBackgroundPlaybackEnabled = audioPreferences.backgroundPlayback.get()",
      "if (!shouldPause) return false",
      "PlaybackSession.setPropertyBoolean(\"pause\", true)",
      "refreshTransportControls()",
    )) {
      assertTrue("pause path missing `$needle`", pause.contains(needle))
    }
  }

  @Test
  fun inAppSheetButtonStillWritesTheAudioPreferenceTheServiceReads() {
    val controls = source("app/infinity/mpvz/ui/player/controls/AudioPlayerControls.kt")
    // The sheet lives in MainActivity, so the button falls through to the preference write; the
    // app-leave hook above is what makes that write take effect on the current session.
    assertTrue(controls.contains("audioPreferences.audioBackgroundPlayback.set(!backgroundPlaybackEnabled)"))
    assertTrue(controls.contains("val backgroundPlaybackEnabled by audioPreferences.audioBackgroundPlayback.collectAsState()"))
  }
}
