package app.infinity.mpvz.ui.browser.music

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackIsolationContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun musicSource(name: String): String =
    File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/music/$name").readText()

  @Test
  fun localMusicRoutesToTheSharedNowPlayingPlayer() {
    val launcher = musicSource("MusicPlaybackLauncher.kt")

    assertTrue(launcher.contains("playSongsOnSharedPlayer(context, songs, selectedIndex, launchSource)"))
    assertFalse(launcher.contains("MusicPlayerActivity::class.java"))
    assertTrue(launcher.contains("playRecentAudioPathIfPresent"))
    assertTrue(launcher.contains("fun isEligibleLocalMusicPath(path: String): Boolean"))
    assertTrue(launcher.contains("scheme != \"file\" && scheme != \"content\""))
    assertTrue(launcher.contains("setOf(\"aa\", \"aax\", \"aaxc\", \"m4b\")"))
    assertTrue(launcher.contains("AudiobookMarkerUtils.isAudiobookPath(path)"))
  }

  @Test
  fun filesystemSearchAudioOpensTheSharedPlayerToo() {
    val screen =
      File(
          root,
          "app/src/main/java/app/infinity/mpvz/ui/browser/filesystem/FileSystemBrowserScreen.kt",
        )
        .readText()

    assertTrue(screen.contains("MusicPlaybackLauncher.playLocalAudioPathsIfPresent"))
    assertTrue(screen.contains("launchSource = \"search\""))
  }

  @Test
  fun musicActivityServiceAndScreenNeverReadVideoSessionOrStatisticsOverlay() {
    val isolatedComponents =
      listOf(
        musicSource("MusicPlayerActivity.kt"),
        musicSource("MusicPlayerViewModel.kt"),
        musicSource("MusicPlaybackService.kt"),
        musicSource("MusicPlayerScreen.kt"),
      )

    isolatedComponents.forEach { source ->
      assertFalse(source.contains("app.infinity.mpvz.ui.player.PlaybackSession"))
      assertFalse(source.contains("app.infinity.mpvz.ui.player.PlayerActivity"))
      assertFalse(source.contains("StatsLuaOverlay"))
    }
    assertTrue(musicSource("MusicPlaybackService.kt").contains("ExoPlayer.Builder(this)"))
  }

  @Test
  fun musicLibraryIndicatorsReadOnlyTheMusicOwnedSnapshot() {
    listOf(
      "MusicLibraryContent.kt",
      "MusicLibraryViewModel.kt",
      "SharedMusicComponents.kt",
    ).forEach { name ->
      val source = musicSource(name)
      assertTrue(source.contains("MusicPlaybackSession"))
      assertFalse(source.contains("app.infinity.mpvz.ui.player.PlaybackSession"))
    }
  }

  @Test
  fun manifestRegistersTheDedicatedMusicComponents() {
    val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

    assertTrue(manifest.contains(".ui.browser.music.MusicPlayerActivity"))
    assertTrue(manifest.contains(".ui.browser.music.MusicPlaybackService"))
    assertTrue(manifest.contains("androidx.media3.session.MediaSessionService"))
  }

  @Test
  fun onlyMusicTabLocalAudioPlaylistsOptIntoTheMusicService() {
    val library = musicSource("MusicLibraryContent.kt")
    val playlistDetail =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/playlist/PlaylistDetailScreen.kt").readText()

    assertTrue(library.contains("useMusicPlayerForLocalAudio = true"))
    assertTrue(playlistDetail.contains("val useMusicPlayerForLocalAudio: Boolean = false"))
    assertTrue(playlistDetail.contains("playlist?.isM3uPlaylist != true"))
    assertTrue(playlistDetail.contains("MusicPlaybackLauncher.playLocalAudioPathsIfPresent"))
  }
}
