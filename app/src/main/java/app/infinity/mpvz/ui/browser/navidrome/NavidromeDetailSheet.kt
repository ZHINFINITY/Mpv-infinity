package app.infinity.mpvz.ui.browser.navidrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.domain.navidrome.NavidromeServer
import app.infinity.mpvz.repository.NavidromeRepository
import app.infinity.mpvz.ui.browser.music.SharedMusicTrackListItem
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
  val repository = koinInject<NavidromeRepository>()
  val title = album?.title ?: artist?.name ?: playlist?.name.orEmpty()
  val subtitle = when {
    album != null -> album.artist
    artist != null -> "${artist.albumCount} albums"
    else -> "${playlist?.songCount ?: 0} tracks"
  }
  val songs = album?.songs ?: playlist?.songs ?: artist?.albums?.flatMap { it.songs }.orEmpty()

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(title, style = MaterialTheme.typography.headlineSmall)
      if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (songs.isNotEmpty()) {
        FilledTonalButton(
          onClick = { viewModel.playAll(context, songs) },
          modifier = Modifier.fillMaxWidth(),
        ) { Text("Play all") }
      }
      LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(songs, key = { it.id }) { song ->
          SharedMusicTrackListItem(
            title = song.title,
            subtitle = "${song.artist} • ${song.album}",
            durationSeconds = song.durationSeconds.toLong(),
            artworkUrl = repository.getSongCoverArtUrl(server, song),
            coverArtSizeDp = 48,
            onClick = { viewModel.playSong(context, song) },
          )
        }
      }
    }
  }
}
