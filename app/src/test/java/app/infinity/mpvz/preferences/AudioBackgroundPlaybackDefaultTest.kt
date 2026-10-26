/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.preferences

import android.app.Application
import android.content.Context
import app.infinity.mpvz.preferences.preference.AndroidPreferenceStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Owner request: music background playback is on by default; video keeps its old default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioBackgroundPlaybackDefaultTest {
  private val prefsName = "audio-background-default-test"

  private fun preferences(): AudioPreferences {
    val context = RuntimeEnvironment.getApplication()
    return AudioPreferences(AndroidPreferenceStore(context, context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)))
  }

  @Before fun clear() {
    RuntimeEnvironment.getApplication().getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun freshInstallPlaysMusicInTheBackgroundButNotVideo() {
    val prefs = preferences()
    assertTrue("music background playback defaults to on", prefs.audioBackgroundPlayback.get())
    assertFalse("video background playback default is unchanged", prefs.backgroundPlayback.get())
  }

  @Test
  fun userWhoTurnedMusicBackgroundOffStaysOff() {
    preferences().audioBackgroundPlayback.set(false)
    assertFalse(preferences().audioBackgroundPlayback.get())
  }
}
