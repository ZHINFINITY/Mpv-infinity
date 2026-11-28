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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.presentation.components.PlayerSheetDragHandle
import app.infinity.mpvz.presentation.components.PlayerSheetLayoutPolicy
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
  val screenHeightDp = LocalConfiguration.current.screenHeightDp
  // Leave the top of the player free so the playing album art stays visible behind the sheet.
  val listMaxHeight = PlayerSheetLayoutPolicy.listMaxHeightDp(screenHeightDp).dp
  val sheetMaxHeight = PlayerSheetLayoutPolicy.listSheetMaxHeightDp(screenHeightDp).dp

  LaunchedEffect(artist) {
    loading = true
    songs = runCatching { MusicLibraryScanner.songsByArtist(context, artist) }.getOrDefault(emptyList())
    loading = false
  }

  PlayerSheet(onDismissRequest, customMaxHeight = sheetMaxHeight) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.medium)) {
      PlayerSheetDragHandle()
      Text(
        text = artist,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.medium),
      )
      when {
        loading -> Text(
          text = "Loading songs…",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.medium),
        )
        songs.isEmpty() -> Text(
          text = "No songs from this artist in your library",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.medium),
        )
        else -> LazyColumn(
          modifier = Modifier.heightIn(max = listMaxHeight),
          contentPadding = PaddingValues(vertical = MaterialTheme.spacing.extraSmall),
        ) {
          itemsIndexed(songs) { index, song ->
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(MaterialTheme.shapes.medium)
                  .clickable {
                    MusicPlaybackLauncher.playSongs(
                      context = context,
                      songs = songs,
                      currentIndex = index,
                      launchSource = "artist_sheet",
                    )
                    onDismissRequest()
                  }
                  .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.small),
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              Text(
                text = song.album,
                style = MaterialTheme.typography.labelMedium,
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
