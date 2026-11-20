/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.net.Uri
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
internal const val MUSIC_METADATA_SONG_ID = "music_song_id"
internal const val MUSIC_METADATA_ALBUM_ID = "music_album_id"
internal const val MUSIC_METADATA_PATH = "music_path"
internal const val MUSIC_METADATA_DATE_ADDED = "music_date_added"
internal const val MUSIC_METADATA_TRACK_NUMBER = "music_track_number"
internal const val MUSIC_METADATA_YEAR = "music_year"
internal const val MUSIC_METADATA_SIZE = "music_size"
internal const val MUSIC_METADATA_LAUNCH_TOKEN = "music_launch_token"
internal const val MUSIC_METADATA_LAUNCH_SOURCE = "music_launch_source"

/** Queue row metadata published by the independent Music playback service. */
internal data class MusicQueueTrackSnapshot(
  val mediaId: String,
  val uri: String?,
  val title: String,
  val artist: String,
  val artworkUri: String?,
  val durationMs: Long,
  val songId: Long = 0L,
  val album: String = "",
  val albumId: Long = 0L,
  val path: String? = null,
  val dateAdded: Long = 0L,
  val trackNumber: Int = 0,
  val year: Int = 0,
  val size: Long = 0L,
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
  val launchToken: String? = null,
  val launchSource: String? = null,
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
  val positionMs: Long = 0L,
  val launchSource: String = "music_library",
)

internal object MusicQueueLaunchStore {
  private val pending = ConcurrentHashMap<String, MusicQueueLaunch>()

  fun stage(
    songs: List<MusicSong>,
    currentIndex: Int,
    launchSource: String = "music_library",
  ): MusicQueueLaunch {
    val launch =
      MusicQueueLaunch(
        token = UUID.randomUUID().toString(),
        songs = songs.toList(),
        currentIndex = currentIndex.coerceIn(songs.indices),
        launchSource = launchSource,
      )
    pending[launch.token] = launch
    return launch
  }

  fun consume(token: String): MusicQueueLaunch? = pending.remove(token)
}

internal fun MusicSong.toMusicMediaItem(
  launchToken: String? = null,
  launchSource: String = "music_library",
): MediaItem {
  val metadata =
    MediaMetadata.Builder()
      .setTitle(title)
      .setArtist(artist)
      .setAlbumTitle(album)
      .setArtworkUri(albumArtUri)
      .setExtras(
        Bundle().apply {
          putLong(MUSIC_METADATA_DURATION_MS, durationMs)
          putLong(MUSIC_METADATA_SONG_ID, id)
          putLong(MUSIC_METADATA_ALBUM_ID, albumId)
          putString(MUSIC_METADATA_PATH, path)
          putLong(MUSIC_METADATA_DATE_ADDED, dateAdded)
          putInt(MUSIC_METADATA_TRACK_NUMBER, trackNumber)
          putInt(MUSIC_METADATA_YEAR, year)
          putLong(MUSIC_METADATA_SIZE, size)
          putString(
            MUSIC_METADATA_SOURCE_EXTENSION,
            path.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.')
              .lowercase(Locale.ROOT).takeIf { it.isNotBlank() },
          )
          putString(MUSIC_METADATA_LAUNCH_TOKEN, launchToken)
          putString(MUSIC_METADATA_LAUNCH_SOURCE, launchSource)
        },
      )
      .build()
  return MediaItem.Builder()
    .setMediaId("music:$id:${uri}")
    .setUri(uri)
    .setMediaMetadata(metadata)
    .build()
}

internal fun MusicQueueTrackSnapshot.toMusicSongOrNull(): MusicSong? {
  val rawUri = uri?.takeIf { it.isNotBlank() } ?: return null
  val parsedUri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
  val scheme = parsedUri.scheme?.lowercase(Locale.ROOT) ?: return null
  if (scheme != "file" && scheme != "content") return null
  val parsedId = mediaId.removePrefix("music:").substringBefore(':').toLongOrNull()
  return MusicSong(
    id = songId.takeIf { it != 0L } ?: parsedId ?: 0L,
    title = title.takeIf { it.isNotBlank() } ?: parsedUri.lastPathSegment ?: "Audio Track",
    artist = artist,
    album = album,
    albumId = albumId,
    durationMs = durationMs.coerceAtLeast(0L),
    path = path?.takeIf { it.isNotBlank() } ?: parsedUri.path?.takeIf { it.isNotBlank() } ?: rawUri,
    uri = parsedUri,
    dateAdded = dateAdded,
    trackNumber = trackNumber,
    year = year,
    albumArtUri = artworkUri?.let { value -> runCatching { Uri.parse(value) }.getOrNull() },
    size = size.coerceAtLeast(0L),
  )
}
