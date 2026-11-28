/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.browser.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Video files must never be routed to the music player from folders, search or queues. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MusicPlaybackLauncherRoutingTest {
  @Test
  fun videoFilesAreNotMusic() {
    assertFalse(MusicPlaybackLauncher.isEligibleLocalMusicPath("/sdcard/Movies/Heat.1080p.mkv"))
    assertFalse(MusicPlaybackLauncher.isEligibleLocalMusicPath("/sdcard/Movies/clip.mp4"))
    assertFalse(MusicPlaybackLauncher.isEligibleLocalMusicPath("/sdcard/Movies/clip.WebM"))
    assertFalse(MusicPlaybackLauncher.isEligibleLocalMusicPath("content://media/external/video/media/42.mp4"))
  }

  @Test
  fun audioFilesStillOpenTheMusicPlayer() {
    assertTrue(MusicPlaybackLauncher.isEligibleLocalMusicPath("/sdcard/Music/song.mp3"))
    assertTrue(MusicPlaybackLauncher.isEligibleLocalMusicPath("/sdcard/Music/song.flac"))
    assertTrue(MusicPlaybackLauncher.isEligibleLocalMusicPath("/sdcard/Music/song.m4a"))
    assertTrue(MusicPlaybackLauncher.isEligibleLocalMusicPath("/sdcard/Music/track.with.dot.flac"))
  }

  @Test
  fun audiobooksStayOutOfTheMusicPlayer() {
    assertFalse(MusicPlaybackLauncher.isEligibleLocalMusicPath("/sdcard/Audiobooks/book.m4b"))
  }
}
