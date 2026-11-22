/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.utils.media.MediaBadge
import app.infinity.mpvz.utils.media.MediaBadges
import coil3.compose.AsyncImage
import org.koin.compose.koinInject

/**
 * Transparent PNG media badges (Elite-Badges) matched from the media title,
 * rendered under the player title.
 */
@Composable
fun MediaBadgesRow(
  mediaTitle: String?,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val preferences = koinInject<AppearancePreferences>()
  val showMediaBadges by preferences.showMediaBadges.collectAsState()

  var badges by remember { mutableStateOf<List<MediaBadge>>(emptyList()) }
  LaunchedEffect(mediaTitle, showMediaBadges) {
    badges = if (showMediaBadges) MediaBadges.matchTitle(context, mediaTitle) else emptyList()
  }

  if (badges.isEmpty()) return
  LazyRow(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier.padding(start = 14.dp, top = 8.dp),
  ) {
    items(badges, key = { it.id }) { badge ->
      AsyncImage(
        model = "file:///android_asset/${badge.assetPath}",
        contentDescription = badge.name,
        contentScale = ContentScale.FillHeight,
        modifier = Modifier.height(16.dp),
      )
    }
  }
}
