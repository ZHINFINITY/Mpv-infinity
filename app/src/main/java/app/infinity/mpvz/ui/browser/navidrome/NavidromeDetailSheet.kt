/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.browser.navidrome

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.infinity.mpvz.ui.player.PlaybackSession
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.navidrome.NavidromeAlbum
import app.infinity.mpvz.domain.navidrome.NavidromeArtist
import app.infinity.mpvz.domain.navidrome.NavidromePlaylist
import app.infinity.mpvz.domain.navidrome.NavidromeServer
import app.infinity.mpvz.domain.navidrome.NavidromeSong
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.repository.NavidromeRepository
import app.infinity.mpvz.ui.browser.music.SharedMusicCarouselSection
import app.infinity.mpvz.ui.browser.music.SharedMusicDetailHeader
import app.infinity.mpvz.ui.browser.music.SharedMusicGridCard
import app.infinity.mpvz.ui.browser.music.SharedMusicTrackListItem
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavidromeDetailSheet(
  uiState: NavidromeUiState,
  server: NavidromeServer,
  viewModel: NavidromeViewModel,
  onDismiss: () -> Unit,
) {
  val album = uiState.detailAlbum
  val artist = uiState.detailArtist
  val playlist = uiState.detailPlaylist

  if (album == null && artist == null && playlist == null) return

  val context = LocalContext.current
  val navidromeRepository = koinInject<NavidromeRepository>()

  val sheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Expanded,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentSessionItem = queueState.currentItem

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding(),
      contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      if (album != null) {
        item {
          val details = buildList {
            album.year?.let { add(it.toString()) }
            if (album.songCount > 0) add("${album.songCount} tracks")
          }.joinToString(" • ")

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            SharedMusicDetailHeader(
              title = album.title,
              subtitle = album.artist.ifBlank { null },
              itemCountText = details.ifBlank { null },
              artworkUrl = navidromeRepository.getCoverArtUrl(server, album.coverArtId, size = 300),
              onPlayAll = { viewModel.playAll(context, album.songs, 0) },
              onShuffle = { viewModel.shufflePlay(context, album.songs) },
              trailingAction = {
                IconButton(onClick = { viewModel.toggleAlbumFavorite(album) }) {
                  Icon(
                    imageVector = if (album.isFavorite) Icons.RoundedFilled.Favorite else Icons.RoundedFilled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (album.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
            )
          }

          if (album.songs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = "Tracks",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }

        itemsIndexed(album.songs, key = { _, s -> s.id }) { index, song ->
          val isTrackPlaying = remember(currentSessionItem, song.id) {
            if (currentSessionItem == null || song.id.isBlank()) false
            else {
              val orig = currentSessionItem.originalUri
              val play = currentSessionItem.playableUri
              orig.contains(song.id, ignoreCase = true) || play.contains(song.id, ignoreCase = true)
            }
          }
          SharedMusicTrackListItem(
            title = song.title,
            subtitle = song.artist,
            durationSeconds = song.durationSeconds.toLong(),
            artworkUrl = navidromeRepository.getSongCoverArtUrl(server, song),
            isPlaying = isTrackPlaying,
            isFavorite = song.isFavorite,
            onFavoriteClick = { viewModel.toggleFavorite(song) },
            onClick = { viewModel.playSong(context, song) },
          )
        }
      } else if (playlist != null) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            val playlistDuration = playlist.durationSeconds.takeIf { it > 0 }
              ?: playlist.songs.sumOf { it.durationSeconds }.takeIf { it > 0 }
            val playlistDurationFormatted = playlistDuration?.let { DateUtils.formatElapsedTime(it.toLong()) }
            val countText = if (playlist.songCount == 1) "1 track" else "${playlist.songCount.coerceAtLeast(playlist.songs.size)} tracks"
            val itemCountText = listOfNotNull(countText, playlistDurationFormatted).joinToString(" • ")

            SharedMusicDetailHeader(
              title = playlist.name,
              subtitle = null,
              itemCountText = itemCountText,
              artworkUrl = navidromeRepository.getCoverArtUrl(server, playlist.coverArtId, size = 300),
              fallbackIcon = if (playlist.id == "virtual_favorites_playlist" || playlist.id == "favorites") Icons.RoundedFilled.Favorite else Icons.RoundedFilled.QueueMusic,
              onPlayAll = { viewModel.playAll(context, playlist.songs, 0) },
              onShuffle = { viewModel.shufflePlay(context, playlist.songs) },
            )
          }

          if (playlist.songs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = "Tracks",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }

        itemsIndexed(playlist.songs, key = { _, s -> s.id }) { index, song ->
          val isTrackPlaying = remember(currentSessionItem, song.id) {
            if (currentSessionItem == null || song.id.isBlank()) false
            else {
              val orig = currentSessionItem.originalUri
              val play = currentSessionItem.playableUri
              orig.contains(song.id, ignoreCase = true) || play.contains(song.id, ignoreCase = true)
            }
          }
          SharedMusicTrackListItem(
            title = song.title,
            subtitle = song.artist,
            durationSeconds = song.durationSeconds.toLong(),
            artworkUrl = navidromeRepository.getSongCoverArtUrl(server, song),
            isPlaying = isTrackPlaying,
            isFavorite = song.isFavorite,
            onFavoriteClick = { viewModel.toggleFavorite(song) },
            onClick = { viewModel.playSong(context, song) },
          )
        }
      } else if (artist != null) {
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            SharedMusicDetailHeader(
              title = artist.name,
              subtitle = "${artist.albumCount} albums",
              artworkUrl = navidromeRepository.getArtistImageUrl(server, artist, size = 300),
              fallbackIcon = Icons.RoundedFilled.Person,
              isCircular = true,
              trailingAction = {
                IconButton(onClick = { viewModel.toggleArtistFavorite(artist) }) {
                  Icon(
                    imageVector = if (artist.isFavorite) Icons.RoundedFilled.Favorite else Icons.RoundedFilled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (artist.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
            )
          }
        }

        if (artist.albums.isNotEmpty()) {
          item {
            SharedMusicCarouselSection(
              title = "Albums (${artist.albums.size})",
              items = artist.albums,
              getId = { it.id },
              getTitle = { it.title },
              getSubtitle = { it.year?.toString() ?: "" },
              getArtworkUrl = { navidromeRepository.getCoverArtUrl(server, it.coverArtId) },
              onClick = { viewModel.openAlbumDetail(it) },
              cardWidth = 140.dp,
            )
          }
        }
      }
    }
  }
}
