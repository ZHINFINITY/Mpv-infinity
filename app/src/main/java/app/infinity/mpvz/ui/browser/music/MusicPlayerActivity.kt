/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import java.util.UUID

/** Full-screen host for ordinary local Music-library and Recently Played audio. */
class MusicPlayerActivity : ComponentActivity() {
  private val viewModel: MusicPlayerViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleLaunchIntent(intent)
    setContent {
      MpvInfinityTheme(showCustomThemeBackdrop = false) {
        MusicPlayerScreen(
          viewModel = viewModel,
          onBackPress = { finish() },
        )
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleLaunchIntent(intent)
  }

  private fun handleLaunchIntent(intent: android.content.Intent) {
    val token = intent.getStringExtra(EXTRA_QUEUE_TOKEN)
    val staged = token?.let(MusicQueueLaunchStore::consume)
    if (staged != null) {
      viewModel.openLaunch(staged)
      return
    }

    // If Android recreates the Activity after process death, the in-memory queue handoff is gone.
    // Keep the explicit track as a safe fallback while attempting token-matched queue recovery.
    val uri = intent.data
    val fallback =
      uri?.let {
        val title = intent.getStringExtra(EXTRA_TITLE)
          ?: it.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
          ?: "Audio Track"
        val song =
          MusicSong(
            id = intent.getLongExtra(EXTRA_SONG_ID, it.toString().hashCode().toLong()),
            title = title,
            artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
            album = intent.getStringExtra(EXTRA_ALBUM).orEmpty(),
            albumId = intent.getLongExtra(EXTRA_ALBUM_ID, 0L),
            durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L),
            path = intent.getStringExtra(EXTRA_PATH) ?: it.path.orEmpty(),
            uri = it,
            dateAdded = 0L,
            albumArtUri = intent.getStringExtra(EXTRA_ARTWORK_URI)?.let(Uri::parse),
          )
        MusicQueueLaunch(
          token = token ?: UUID.randomUUID().toString(),
          songs = listOf(song),
          currentIndex = 0,
          launchSource = intent.getStringExtra(EXTRA_LAUNCH_SOURCE) ?: "music_library",
        )
      }
    if (token == null && fallback != null) {
      viewModel.openLaunch(fallback)
    } else {
      viewModel.restoreLaunch(token, fallback)
    }
  }

  companion object {
    const val EXTRA_QUEUE_TOKEN = "music_queue_launch_token"
    const val EXTRA_SONG_ID = "music_song_id"
    const val EXTRA_TITLE = "music_title"
    const val EXTRA_ARTIST = "music_artist"
    const val EXTRA_ALBUM = "music_album"
    const val EXTRA_ALBUM_ID = "music_album_id"
    const val EXTRA_DURATION_MS = "music_duration_ms"
    const val EXTRA_PATH = "music_path"
    const val EXTRA_ARTWORK_URI = "music_artwork_uri"
    const val EXTRA_LAUNCH_SOURCE = "music_launch_source"
  }
}
