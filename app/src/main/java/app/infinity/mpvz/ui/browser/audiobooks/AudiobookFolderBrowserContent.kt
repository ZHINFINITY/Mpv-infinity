package app.infinity.mpvz.ui.browser.audiobooks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.domain.audiobook.AudiobookFolderTree
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons

@Composable
internal fun AudiobookFolderBrowserContent(
  tree: AudiobookFolderTree?,
  isLoading: Boolean,
  error: String?,
  opening: Boolean,
  bottomPadding: Dp,
  onChooseFolder: () -> Unit,
  onRetry: () -> Unit,
  onExit: () -> Unit,
  onPlay: (folderUri: String, selectedTrackUri: String?) -> Unit,
) {
  val rootUri = tree?.rootUri
  var currentFolderUri by rememberSaveable(rootUri) { mutableStateOf(rootUri.orEmpty()) }
  LaunchedEffect(rootUri) {
    if (rootUri != null) currentFolderUri = rootUri
  }
  val listing = tree?.listing(currentFolderUri)
  val folderTracks = remember(tree, currentFolderUri) { tree?.tracksUnder(currentFolderUri).orEmpty() }

  BackHandler(enabled = tree != null) {
    if (currentFolderUri != rootUri) {
      currentFolderUri = listing?.parentUri ?: rootUri.orEmpty()
    } else {
      onExit()
    }
  }

  Column(Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      IconButton(
        onClick = {
          if (currentFolderUri != rootUri) currentFolderUri = listing?.parentUri ?: rootUri.orEmpty()
          else onExit()
        },
      ) {
        Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.audiobook_back_to_library))
      }
      Text(
        text = listing?.folder?.name ?: stringResource(R.string.audiobook_folders),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (folderTracks.isNotEmpty()) {
        FilledTonalButton(onClick = { onPlay(currentFolderUri, null) }, enabled = !opening) {
          Text(stringResource(R.string.audiobook_play_folder))
        }
      }
      TextButton(onClick = onChooseFolder) { Text(stringResource(R.string.audiobook_change_folder)) }
    }

    if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!error.isNullOrBlank()) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.ui_refresh)) }
      }
    }

    when {
      tree == null && !isLoading -> Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(Icons.RoundedFilled.FolderOpen, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.audiobook_choose_folder_hint), modifier = Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onChooseFolder) { Text(stringResource(R.string.audiobook_choose_folder)) }
      }
      tree != null && listing == null -> Text(
        stringResource(R.string.audiobook_folder_unavailable),
        modifier = Modifier.padding(20.dp),
        color = MaterialTheme.colorScheme.error,
      )
      listing != null -> LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (listing.folders.isEmpty() && listing.tracks.isEmpty()) {
          item {
            Text(
              stringResource(R.string.audiobook_folder_empty),
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
        items(listing.folders, key = { "folder:${it.uri}" }) { folder ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable { currentFolderUri = folder.uri }.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(Icons.RoundedFilled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(folder.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { onPlay(folder.uri, null) }, enabled = !opening) {
              Text(stringResource(R.string.audiobook_play_folder))
            }
          }
        }
        items(listing.tracks, key = { "track:${it.uri}" }) { track ->
          Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !opening) { onPlay(currentFolderUri, track.uri) }
              .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Icon(Icons.RoundedFilled.Audiotrack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
              Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(
                track.mimeType?.takeIf(String::isNotBlank) ?: stringResource(R.string.audiobook_audio_file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Icon(Icons.RoundedFilled.PlayCircle, contentDescription = stringResource(R.string.audiobook_play_track), tint = MaterialTheme.colorScheme.primary)
          }
        }
      }
    }
  }
}
