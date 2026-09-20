package app.infinity.mpvz.ui.browser.navidrome

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.infinity.mpvz.domain.navidrome.NavidromeAlbum
import app.infinity.mpvz.domain.navidrome.NavidromeArtist
import app.infinity.mpvz.domain.navidrome.NavidromeMusicTab
import app.infinity.mpvz.domain.navidrome.NavidromePlaylist
import app.infinity.mpvz.domain.navidrome.NavidromeServer
import app.infinity.mpvz.domain.navidrome.NavidromeSong
import app.infinity.mpvz.repository.NavidromeRepository
import app.infinity.mpvz.ui.browser.music.SharedMusicTrackListItem
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlaybackSession
import org.koin.compose.koinInject

@Composable
fun NavidromeMusicView(
  uiState: NavidromeUiState,
  server: NavidromeServer,
  pagerState: PagerState,
  visibleTabs: List<NavidromeMusicTab>,
  onTabSelected: (NavidromeMusicTab) -> Unit,
  onSongClick: (NavidromeSong) -> Unit,
  onAlbumClick: (NavidromeAlbum) -> Unit,
  onArtistClick: (NavidromeArtist) -> Unit,
  onPlaylistClick: (NavidromePlaylist) -> Unit,
  onToggleFavorite: (NavidromeSong) -> Unit,
  navigationBarHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val repository = koinInject<NavidromeRepository>()
  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentItem = queueState.currentItem

  HorizontalPager(
    state = pagerState,
    modifier = modifier.fillMaxSize(),
    beyondViewportPageCount = 1,
    key = { page -> visibleTabs.getOrNull(page) ?: page },
  ) { page ->
    val tab = visibleTabs.getOrNull(page) ?: NavidromeMusicTab.HOME
    val bottomPadding = navigationBarHeight + 84.dp
    when (tab) {
      NavidromeMusicTab.HOME -> {
        val homeItems = uiState.jumpBackIn
        if (uiState.isLoading && homeItems.isEmpty() && uiState.playlists.isEmpty()) {
          LoadingBox()
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(homeItems, key = { it.id }) { song ->
              SongRow(song, repository, server, currentItem, onSongClick, onToggleFavorite)
            }
            items(uiState.playlists, key = { it.id }) { playlist ->
              SharedMusicTrackListItem(
                title = playlist.name,
                subtitle = formatPlaylist(playlist),
                artworkUrl = repository.getCoverArtUrl(server, playlist.coverArtId),
                coverArtSizeDp = 52,
                onClick = { onPlaylistClick(playlist) },
              )
            }
          }
        }
      }
      NavidromeMusicTab.TRACKS -> {
        if (uiState.isLoading && uiState.tracks.isEmpty()) LoadingBox() else LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
        ) {
          items(uiState.tracks, key = { it.id }) { song ->
            SongRow(song, repository, server, currentItem, onSongClick, onToggleFavorite)
          }
        }
      }
      NavidromeMusicTab.ALBUMS -> {
        if (uiState.isLoading && uiState.albums.isEmpty()) LoadingBox() else LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
        ) {
          items(uiState.albums, key = { it.id }) { album ->
            SharedMusicTrackListItem(
              title = album.title,
              subtitle = album.artist,
              durationSeconds = album.durationSeconds.toLong().takeIf { it > 0 },
              artworkUrl = repository.getCoverArtUrl(server, album.coverArtId),
              coverArtSizeDp = 56,
              onClick = { onAlbumClick(album) },
            )
          }
        }
      }
      NavidromeMusicTab.ARTISTS -> {
        if (uiState.isLoading && uiState.artists.isEmpty()) LoadingBox() else LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
        ) {
          items(uiState.artists, key = { it.id }) { artist ->
            SharedMusicTrackListItem(
              title = artist.name,
              subtitle = "${artist.albumCount} albums",
              artworkUrl = repository.getArtistImageUrl(server, artist),
              coverArtSizeDp = 56,
              onClick = { onArtistClick(artist) },
            )
          }
        }
      }
      NavidromeMusicTab.PLAYLISTS -> {
        if (uiState.isLoading && uiState.playlists.isEmpty()) LoadingBox() else LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
        ) {
          items(uiState.playlists, key = { it.id }) { playlist ->
            SharedMusicTrackListItem(
              title = playlist.name,
              subtitle = formatPlaylist(playlist),
              artworkUrl = repository.getCoverArtUrl(server, playlist.coverArtId),
              coverArtSizeDp = 56,
              onClick = { onPlaylistClick(playlist) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LoadingBox() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}

@Composable
private fun SongRow(
  song: NavidromeSong,
  repository: NavidromeRepository,
  server: NavidromeServer,
  currentItem: PlaybackItem?,
  onSongClick: (NavidromeSong) -> Unit,
  onToggleFavorite: (NavidromeSong) -> Unit,
) {
  val isPlaying = remember(currentItem, song.id) {
    currentItem?.originalUri?.contains(song.id, ignoreCase = true) == true ||
      currentItem?.playableUri?.contains(song.id, ignoreCase = true) == true
  }
  SharedMusicTrackListItem(
    title = song.title,
    subtitle = "${song.artist} • ${song.album}",
    durationSeconds = song.durationSeconds.toLong(),
    artworkUrl = repository.getSongCoverArtUrl(server, song),
    isPlaying = isPlaying,
    coverArtSizeDp = 52,
    onClick = { onSongClick(song) },
  )
}

private fun formatPlaylist(playlist: NavidromePlaylist): String {
  val tracks = if (playlist.songCount == 1) "1 track" else "${playlist.songCount} tracks"
  val duration = playlist.durationSeconds.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it.toLong()) }
  return listOfNotNull(tracks, duration).joinToString(" • ")
}
