/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val MUSIC_METADATA_DURATION_MS = "music_duration_ms"
internal const val MUSIC_METADATA_SOURCE_EXTENSION = "music_source_extension"

/** Queue row metadata published by the independent Music playback service. */
internal data class MusicQueueTrackSnapshot(
  val mediaId: String,
  val uri: String?,
  val title: String,
  val artist: String,
  val artworkUri: String?,
  val durationMs: Long,
)

/** The Music player publishes only its own Media3 state; it never mirrors video/MPV properties. */
internal data class MusicPlaybackSnapshot(
  val currentMediaId: String? = null,
  val currentUri: String? = null,
  val title: String? = null,
  val artist: String? = null,
  val album: String? = null,
  val artworkUri: String? = null,
  val sourceExtension: String? = null,
  val currentIndex: Int = -1,
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val repeatMode: Int = Player.REPEAT_MODE_OFF,
  val shuffleEnabled: Boolean = false,
  val playbackSpeed: Float = 1f,
  val queue: List<MusicQueueTrackSnapshot> = emptyList(),
)

internal object MusicPlaybackSession {
  private val mutableState = MutableStateFlow(MusicPlaybackSnapshot())
  val state: StateFlow<MusicPlaybackSnapshot> = mutableState.asStateFlow()

  internal fun publish(snapshot: MusicPlaybackSnapshot) {
    mutableState.value = snapshot
  }

  internal fun reset() {
    mutableState.value = MusicPlaybackSnapshot()
  }
}

/** One-shot in-process handoff from the Music library into its playback Activity. */
internal data class MusicQueueLaunch(
  val token: String,
  val songs: List<MusicSong>,
  val currentIndex: Int,
)

internal object MusicQueueLaunchStore {
  private val pending = ConcurrentHashMap<String, MusicQueueLaunch>()

  fun stage(
    songs: List<MusicSong>,
    currentIndex: Int,
  ): MusicQueueLaunch {
    val launch =
      MusicQueueLaunch(
        token = UUID.randomUUID().toString(),
        songs = songs.toList(),
        currentIndex = currentIndex.coerceIn(songs.indices),
      )
    pending[launch.token] = launch
    return launch
  }

  fun consume(token: String): MusicQueueLaunch? = pending.remove(token)
}

internal fun MusicSong.toMusicMediaItem(): MediaItem {
  val metadata =
    MediaMetadata.Builder()
      .setTitle(title)
      .setArtist(artist)
      .setAlbumTitle(album)
      .setArtworkUri(albumArtUri)
      .setExtras(
        Bundle().apply {
          putLong(MUSIC_METADATA_DURATION_MS, durationMs)
          putString(
            MUSIC_METADATA_SOURCE_EXTENSION,
            path.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.')
              .lowercase(Locale.ROOT).takeIf { it.isNotBlank() },
          )
        },
      )
      .build()
  return MediaItem.Builder()
    .setMediaId("music:$id:${uri}")
    .setUri(uri)
    .setMediaMetadata(metadata)
    .build()
}
