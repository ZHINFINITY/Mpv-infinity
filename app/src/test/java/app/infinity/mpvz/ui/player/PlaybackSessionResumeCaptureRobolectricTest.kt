/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import `is`.xyz.mpv.MPVLib
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [35],
  application = Application::class,
  shadows = [ShadowPlaybackMpvLib::class],
  instrumentedPackages = ["is.xyz.mpv"],
)
class PlaybackSessionResumeCaptureRobolectricTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before
  fun resetCore() {
    PlaybackSession.destroy()
    ShadowPlaybackMpvLib.reset()
  }

  @After
  fun retireCore() {
    PlaybackSession.destroy()
  }

  @Test
  fun loadingCaptureUsesBothPauseRestoreIntents() {
    val item = PlaybackItem.fromUri("file:///tmp/resume-capture.mp4", stableId = "resume-capture")

    for (paused in listOf(false, true)) {
      assertTrue(initialize("resume-capture-$paused").getOrThrow())
      val generation =
        PlaybackSession.load(
          item = item,
          positionRestoreOverride = PlaybackPositionRestoreOverride(37.25, paused),
        )

      assertEquals(PlaybackPhase.LOADING, PlaybackSession.state.value.phase)
      assertEquals(generation, PlaybackSession.state.value.generation)
      val capture = PlaybackSession.captureResumeState(fallbackPositionSeconds = 0.0)

      assertEquals(37.25, capture.position.positionSeconds, 0.0)
      assertEquals(paused, capture.paused)
    }
  }

  private fun initialize(key: String): Result<Boolean> =
    PlaybackSession.initialize(
      context = context,
      configDir = "unused/config",
      cacheDir = "unused/cache",
      coreConfigurationKey = key,
      initOptions = {},
      postInitOptions = {},
      observeProperties = {
        PlaybackSession.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
      },
    )
}
