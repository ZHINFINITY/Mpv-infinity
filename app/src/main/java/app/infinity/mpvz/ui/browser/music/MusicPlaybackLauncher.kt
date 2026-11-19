/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.content.Context
import android.content.Intent
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PreparedPlaybackLaunchStore
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.utils.media.fileExtension
import app.infinity.mpvz.utils.storage.FileTypeUtils

/** Builds audio queue items from music metadata rather than routing songs through video models. */
internal fun MusicSong.toMusicPlaybackItem(): PlaybackItem =
  PlaybackItem.fromUri(
    uri = uri.toString(),
    title = title,
    artist = artist,
    mimeType = "audio/*",
    artworkUri = albumArtUri?.toString(),
    durationSeconds = durationMs.takeIf { it > 0L }?.div(1000L)?.toInt(),
  )

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
    val selectedSong = songs[selectedIndex]
    val launchToken =
      PreparedPlaybackLaunchStore.stage(
        items = songs.map { it.toMusicPlaybackItem() },
        currentIndex = selectedIndex,
        isExplicitQueue = true,
      )
    val intent = Intent(Intent.ACTION_VIEW, selectedSong.uri).apply {
      setClass(context, PlayerActivity::class.java)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("internal_launch", true)
      putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
      putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, launchToken)
      putExtra("playlist_index", selectedIndex)
      putExtra("launch_source", launchSource)
      putExtra("media_library_audio", true)
      putExtra("is_audio", true)
      putExtra("title", selectedSong.title)
    }
    context.startActivity(intent)
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
    if (extension !in FileTypeUtils.AUDIO_EXTENSIONS) return false
    val song = MusicLibraryScanner.resolveSongByPath(context, path, fallbackTitle) ?: return false
    playSong(context, song, launchSource)
    return true
  }
}
