/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.browser.music.MusicLibraryScanner
import app.infinity.mpvz.ui.browser.music.MusicPlaybackLauncher
import app.infinity.mpvz.ui.browser.music.MusicSong
import app.infinity.mpvz.ui.theme.spacing

/**
 * Songs by one artist, opened from the Now Playing artist line. Reuses the cached library scan,
 * so opening it never triggers a second MediaStore pass.
 */
@Composable
fun ArtistSongsSheet(
  artist: String,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var songs by remember(artist) { mutableStateOf<List<MusicSong>>(emptyList()) }
  var loading by remember(artist) { mutableStateOf(true) }

  LaunchedEffect(artist) {
    loading = true
    songs = runCatching { MusicLibraryScanner.songsByArtist(context, artist) }.getOrDefault(emptyList())
    loading = false
  }

  PlayerSheet(onDismissRequest) {
    Column(modifier = modifier.padding(vertical = MaterialTheme.spacing.medium)) {
      Text(
        text = artist,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.large, vertical = MaterialTheme.spacing.small),
      )
      when {
        loading -> Text(
          text = "Loading songs…",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.large, vertical = MaterialTheme.spacing.medium),
        )
        songs.isEmpty() -> Text(
          text = "No songs from this artist in your library",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.large, vertical = MaterialTheme.spacing.medium),
        )
        else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
          itemsIndexed(songs) { index, song ->
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable {
                    MusicPlaybackLauncher.playSongs(
                      context = context,
                      songs = songs,
                      currentIndex = index,
                      launchSource = "artist_sheet",
                    )
                    onDismissRequest()
                  }
                  .padding(horizontal = MaterialTheme.spacing.large, vertical = MaterialTheme.spacing.medium),
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              Text(
                text = song.album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}
