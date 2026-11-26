/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.infinity.mpvz.domain.audiobook.AudiobookMarkerUtils
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PreparedPlaybackLaunchStore
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.utils.media.fileExtension
import app.infinity.mpvz.utils.storage.FileTypeUtils
import java.util.Locale

/** One launch path for the Music library and audio entries in Recently Played. */
internal object MusicPlaybackLauncher {
  fun playSong(
    context: Context,
    song: MusicSong,
    launchSource: String = "music_library",
  ) = playSongs(
    context = context,
    songs = listOf(song),
    currentIndex = 0,
    launchSource = launchSource,
  )

  fun playSongs(
    context: Context,
    songs: List<MusicSong>,
    currentIndex: Int = 0,
    launchSource: String = "music_library",
  ) {
    if (songs.isEmpty()) return
    val selectedIndex = currentIndex.coerceIn(songs.indices)
    // Library, search and playlist audio all open the shared Now Playing screen
    // (PlayerActivity), the same one normal playback uses; MusicPlayerActivity
    // opened a second, different-looking player for these songs.
    playSongsOnSharedPlayer(context, songs, selectedIndex, launchSource)
  }

  suspend fun playRecentAudioPathIfPresent(
    context: Context,
    path: String,
    fallbackTitle: String?,
    launchSource: String,
  ): Boolean {
    val extension =
      path.substringBefore('?').substringBefore('#').fileExtension()
        .ifBlank { fallbackTitle.orEmpty().fileExtension() }
    if (extension !in FileTypeUtils.AUDIO_EXTENSIONS || !isEligibleLocalMusicPath(path)) return false
    val song = MusicLibraryScanner.resolveSongByPath(context, path, fallbackTitle) ?: return false
    if (isAudiobookSong(song)) return false
    playSong(context, song, launchSource)
    return true
  }

  fun isEligibleLocalMusicPath(path: String): Boolean {
    if (path.isBlank()) return false
    val scheme = runCatching { Uri.parse(path).scheme?.lowercase(Locale.ROOT) }.getOrNull()
    if (scheme != null && scheme != "file" && scheme != "content") return false
    val extension = path.substringBefore('?').substringBefore('#').fileExtension()
    if (extension in AUDIOBOOK_EXTENSIONS) return false
    return !AudiobookMarkerUtils.isAudiobookPath(path)
  }

  suspend fun playLocalAudioPathsIfPresent(
    context: Context,
    paths: List<String>,
    fallbackTitles: List<String?>,
    currentIndex: Int,
    launchSource: String,
  ): Boolean {
    if (paths.isEmpty() || paths.size != fallbackTitles.size) return false
    if (!paths.all(::isEligibleLocalMusicPath)) return false

    val songs = ArrayList<MusicSong>(paths.size)
    for (index in paths.indices) {
      val song = MusicLibraryScanner.resolveSongByPath(context, paths[index], fallbackTitles[index]) ?: return false
      songs += song
    }
    playSongs(context, songs, currentIndex.coerceIn(songs.indices), launchSource)
    return true
  }

  private fun isAudiobookSong(song: MusicSong): Boolean {
    val extension =
      song.path.substringBefore('?').substringBefore('#').fileExtension()
        .ifBlank { song.title.fileExtension() }
    return extension in AUDIOBOOK_EXTENSIONS || AudiobookMarkerUtils.isAudiobookPath(song.path)
  }

  private fun playSongsOnSharedPlayer(
    context: Context,
    songs: List<MusicSong>,
    selectedIndex: Int,
    launchSource: String,
  ) {
    val selectedSong = songs[selectedIndex]
    val token =
      PreparedPlaybackLaunchStore.stage(
        items = songs.map { song ->
          PlaybackItem.fromUri(
            uri = song.uri.toString(),
            title = song.title,
            artist = song.artist,
            mimeType = "audio/*",
            artworkUri = song.albumArtUri?.toString(),
            durationSeconds = song.durationMs.takeIf { it > 0L }?.div(1_000L)?.toInt(),
          )
        },
        currentIndex = selectedIndex,
        isExplicitQueue = true,
      )
    val intent = Intent(Intent.ACTION_VIEW, selectedSong.uri).apply {
      setClass(context, PlayerActivity::class.java)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("internal_launch", true)
      putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
      putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, token)
      putExtra("playlist_index", selectedIndex)
      putExtra("launch_source", launchSource)
      putExtra("media_library_audio", true)
      putExtra("is_audio", true)
      putExtra("title", selectedSong.title)
    }
    context.startActivity(intent)
  }

  private val AUDIOBOOK_EXTENSIONS = setOf("aa", "aax", "aaxc", "m4b")
}
