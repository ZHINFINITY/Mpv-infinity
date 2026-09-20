package app.infinity.mpvz.ui.browser.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.MediaServerPreferences
import app.infinity.mpvz.preferences.MusicSourceProvider
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import org.koin.compose.koinInject

@Composable
fun MusicSourceChooser(
  hasJellyfin: Boolean,
  hasNavidrome: Boolean,
  onManageServers: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val preferences = koinInject<MediaServerPreferences>()
  val currentSource by preferences.musicSourceProvider.collectAsState()
  var expanded by remember { mutableStateOf(false) }

  val sourceTitle = when (currentSource) {
    MusicSourceProvider.LOCAL -> stringResource(R.string.music_source_local)
    MusicSourceProvider.JELLYFIN -> stringResource(R.string.music_source_jellyfin)
    MusicSourceProvider.NAVIDROME -> stringResource(R.string.music_source_navidrome)
  }

  Surface(
    modifier = modifier
      .clip(RoundedCornerShape(20.dp))
      .clickable { expanded = true },
    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.82f),
  ) {
    Row(
      modifier = Modifier,
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      Icon(Icons.RoundedFilled.Equalizer, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(sourceTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1)
      Spacer(Modifier.width(4.dp))
      Icon(Icons.RoundedFilled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      MusicSourceItem(stringResource(R.string.music_source_local), Icons.RoundedFilled.Folder, currentSource == MusicSourceProvider.LOCAL) {
        preferences.musicSourceProvider.set(MusicSourceProvider.LOCAL)
        expanded = false
      }
      if (hasJellyfin) {
        MusicSourceItem(stringResource(R.string.music_source_jellyfin), Icons.RoundedFilled.Language, currentSource == MusicSourceProvider.JELLYFIN) {
          preferences.musicSourceProvider.set(MusicSourceProvider.JELLYFIN)
          expanded = false
        }
      }
      if (hasNavidrome) {
        MusicSourceItem(stringResource(R.string.music_source_navidrome), Icons.RoundedFilled.Equalizer, currentSource == MusicSourceProvider.NAVIDROME) {
          preferences.musicSourceProvider.set(MusicSourceProvider.NAVIDROME)
          expanded = false
        }
      }
      DropdownMenuItem(
        text = { Text(stringResource(R.string.music_source_manage_servers)) },
        leadingIcon = { Icon(Icons.RoundedFilled.Settings, contentDescription = null) },
        onClick = {
          expanded = false
          onManageServers()
        },
      )
    }
  }
}

@Composable
private fun MusicSourceItem(title: String, icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
  DropdownMenuItem(
    text = {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title)
        if (selected) {
          Spacer(Modifier.weight(1f))
          Text("∞", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
      }
    },
    leadingIcon = { Icon(icon, contentDescription = null) },
    onClick = onClick,
  )
}
