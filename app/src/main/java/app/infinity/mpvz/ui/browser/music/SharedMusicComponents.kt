/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.music

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.BrowserPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.controls.components.MiniAudioVisualizer
import app.infinity.mpvz.ui.theme.AppShapeScale
import org.koin.compose.koinInject

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedMusicTrackListItem(
  title: String,
  subtitle: String? = null,
  artworkUrl: String? = null,
  albumArtUri: Uri? = null,
  durationSeconds: Long? = null,
  trailingText: String? = null,
  isPlaying: Boolean = false,
  isSelected: Boolean = false,
  fallbackIcon: AppIcon = Icons.RoundedFilled.Audiotrack,
  isCircular: Boolean = false,
  coverArtSizeDp: Int = 48,
  isFavorite: Boolean = false,
  onFavoriteClick: (() -> Unit)? = null,
  trailingContent: (@Composable () -> Unit)? = null,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  swipeIdentity: String = title,
  isWatched: Boolean? = null,
  onSwipeAction: ((Any) -> Unit)? = null,
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 3.dp)
      .clip(AppShapeScale.large)
      .semantics { selected = isSelected }
      .then(
        if (onLongClick != null) {
          Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
          Modifier.clickable(onClick = onClick)
        }
      ),
    shape = AppShapeScale.large,
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
      else MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isPlaying) 0.35f else 0f),
      contentColor = MaterialTheme.colorScheme.onSurface,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val itemArtSize = if (isCircular) (coverArtSizeDp * 1.3f).toInt().coerceAtLeast(48).dp else coverArtSizeDp.dp
      Box(
        modifier = Modifier
          .size(itemArtSize)
          .clip(if (isCircular) CircleShape else AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        when {
          !artworkUrl.isNullOrBlank() -> {
            RemoteImage(
              url = artworkUrl,
              contentDescription = title,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
          albumArtUri != null -> {
            LocalAlbumArtImage(
              uri = albumArtUri,
              contentDescription = title,
              modifier = Modifier.fillMaxSize(),
            )
          }
          else -> {
            Icon(
              imageVector = fallbackIcon,
              contentDescription = null,
              tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(if (isCircular) 28.dp else 20.dp),
            )
          }
        }

        if (isPlaying && !isSelected) {
          val paused by PlaybackSession.propBoolean["pause"].collectAsState()
          val isPlaybackActive = paused != true
          Box(
            modifier = Modifier
              .fillMaxSize()
              .then(if (isCircular) Modifier.clip(CircleShape) else Modifier)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
          ) {
            MiniAudioVisualizer(
              isPlaying = isPlaybackActive,
              color = Color.White,
              modifier = Modifier.size(width = 18.dp, height = 16.dp),
            )
          }
        }
        if (isSelected) {
          Icon(
            imageVector = Icons.RoundedFilled.CheckCircle,
            contentDescription = "Selected",
            tint = Color.White,
            modifier = Modifier.align(if (isCircular) Alignment.Center else Alignment.TopEnd).padding(4.dp).size(24.dp),
          )
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
          ),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (durationSeconds != null && durationSeconds > 0) {
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = DateUtils.formatElapsedTime(durationSeconds),
          style = MaterialTheme.typography.labelMedium,
          color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else if (!trailingText.isNullOrBlank()) {
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = trailingText,
          style = MaterialTheme.typography.labelMedium,
          color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (onFavoriteClick != null) {
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
          onClick = onFavoriteClick,
          modifier = Modifier.size(36.dp),
        ) {
          Icon(
            imageVector = if (isFavorite) Icons.RoundedFilled.Favorite else Icons.RoundedFilled.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
          )
        }
      }

      if (trailingContent != null) {
        Spacer(modifier = Modifier.width(4.dp))
        trailingContent()
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedMusicGridCard(
  title: String,
  subtitle: String? = null,
  thirdLine: String? = null,
  artworkUrl: String? = null,
  albumArtUri: Uri? = null,
  fallbackIcon: AppIcon = Icons.RoundedFilled.Audiotrack,
  isCircular: Boolean = false,
  cardWidth: Dp? = null,
  isSelected: Boolean = false,
  isPlaying: Boolean = false,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier
      .then(if (cardWidth != null) Modifier.width(cardWidth) else Modifier.fillMaxWidth())
      .clip(AppShapeScale.large)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    shape = AppShapeScale.large,
    colors = CardDefaults.cardColors(
      containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> Color.Transparent
      }
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(if (isCircular) 14.dp else 8.dp),
      horizontalAlignment = if (isCircular) Alignment.CenterHorizontally else Alignment.Start,
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .clip(if (isCircular) CircleShape else AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        when {
          !artworkUrl.isNullOrBlank() -> {
            RemoteImage(
              url = artworkUrl,
              contentDescription = title,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
          albumArtUri != null -> {
            LocalAlbumArtImage(
              uri = albumArtUri,
              contentDescription = title,
              modifier = Modifier.fillMaxSize(),
            )
          }
          else -> {
            Icon(
              imageVector = fallbackIcon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(if (isCircular) 48.dp else 36.dp),
            )
          }
        }

        if (isSelected) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .then(if (isCircular) Modifier.clip(CircleShape) else Modifier)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = "Selected",
              tint = Color.White,
              modifier = Modifier.size(36.dp),
            )
          }
        } else if (isPlaying) {
          val paused by PlaybackSession.propBoolean["pause"].collectAsState()
          val isPlaybackActive = paused != true
          Box(
            modifier = Modifier
              .fillMaxSize()
              .then(if (isCircular) Modifier.clip(CircleShape) else Modifier)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
          ) {
            MiniAudioVisualizer(
              isPlaying = isPlaybackActive,
              color = Color.White,
              modifier = Modifier.size(width = 28.dp, height = 24.dp),
              barCount = 4,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(if (isCircular) 8.dp else 6.dp))

      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isCircular) Alignment.CenterHorizontally else Alignment.Start,
      ) {
        Text(
          text = title,
          style = if (isCircular) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
          else MaterialTheme.typography.bodyLarge.copy(
            fontWeight = if (isPlaying) FontWeight.ExtraBold else FontWeight.Bold
          ),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
          textAlign = if (isCircular) TextAlign.Center else TextAlign.Start,
          modifier = Modifier.fillMaxWidth(),
        )

        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (isCircular) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
          )
        }

        if (!thirdLine.isNullOrBlank()) {
          Text(
            text = thirdLine,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = if (isCircular) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
  }
}

@Composable
fun SharedMusicSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  onSeeAllClick: (() -> Unit)? = null,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onBackground,
    )
    if (onSeeAllClick != null) {
      Text(
        text = "See all",
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable(onClick = onSeeAllClick)
          .padding(horizontal = 8.dp, vertical = 4.dp),
      )
    }
  }
}

@Composable
fun <T> SharedCompactTrackGridSection(
  title: String,
  tracks: List<T>,
  getId: (T) -> String,
  getTitle: (T) -> String,
  getSubtitle: (T) -> String,
  getArtworkUrl: (T) -> String?,
  onTrackClick: (T) -> Unit,
  modifier: Modifier = Modifier,
  onSeeAllClick: (() -> Unit)? = null,
) {
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val rowsCount = when {
    tracks.size >= 6 -> 3
    tracks.size >= 3 -> 2
    else -> 1
  }
  val rowHeight = if (isLandscape) 70 else 64
  val itemWidth = if (isLandscape) 320.dp else 280.dp
  val gridHeight = (rowsCount * rowHeight + (rowsCount - 1) * 12).dp

  Column(modifier = modifier) {
    SharedMusicSectionHeader(
      title = title,
      onSeeAllClick = onSeeAllClick,
    )
    LazyHorizontalGrid(
      rows = GridCells.Fixed(rowsCount),
      modifier = Modifier
        .height(gridHeight)
        .padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(tracks, key = { getId(it) }) { track ->
        val artworkUrl = getArtworkUrl(track)
        Row(
          modifier = Modifier
            .width(itemWidth)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onTrackClick(track) },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier = Modifier
              .size(56.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
          ) {
            if (!artworkUrl.isNullOrBlank()) {
              RemoteImage(
                url = artworkUrl,
                contentDescription = getTitle(track),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
            } else {
              Icon(
                imageVector = Icons.RoundedFilled.Audiotrack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
              )
            }
          }
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = getTitle(track),
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val subtitle = getSubtitle(track)
            if (subtitle.isNotBlank()) {
              Text(
                text = subtitle,
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

@Composable
fun <T> SharedMusicCarouselSection(
  title: String,
  items: List<T>,
  getId: (T) -> String,
  getTitle: (T) -> String,
  getSubtitle: (T) -> String,
  getArtworkUrl: (T) -> String?,
  onClick: (T) -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: ((T) -> Unit)? = null,
  onSeeAllClick: (() -> Unit)? = null,
  isCircular: Boolean = false,
  cardWidth: Dp = if (isCircular) 130.dp else 140.dp,
  fallbackIcon: AppIcon = if (isCircular) Icons.RoundedFilled.Person else Icons.RoundedFilled.Audiotrack,
  getFallbackIcon: ((T) -> AppIcon)? = null,
) {
  Column(modifier = modifier) {
    SharedMusicSectionHeader(
      title = title,
      onSeeAllClick = onSeeAllClick,
    )
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(if (isCircular) 16.dp else 12.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
      items(items, key = { getId(it) }) { item ->
        SharedMusicGridCard(
          title = getTitle(item),
          subtitle = getSubtitle(item),
          artworkUrl = getArtworkUrl(item),
          fallbackIcon = getFallbackIcon?.invoke(item) ?: fallbackIcon,
          isCircular = isCircular,
          cardWidth = cardWidth,
          onClick = { onClick(item) },
          onLongClick = onLongClick?.let { { it(item) } },
        )
      }
    }
  }
}

@Composable
fun SharedMusicDetailHeader(
  title: String,
  subtitle: String? = null,
  itemCountText: String? = null,
  artworkUrl: String? = null,
  fallbackIcon: AppIcon = Icons.RoundedFilled.Audiotrack,
  isCircular: Boolean = false,
  onPlayAll: (() -> Unit)? = null,
  onShuffle: (() -> Unit)? = null,
  trailingAction: (@Composable () -> Unit)? = null,
  playButtonText: String = "Play",
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(64.dp)
          .clip(if (isCircular) CircleShape else RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        if (!artworkUrl.isNullOrBlank()) {
          RemoteImage(
            url = artworkUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
          )
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (!itemCountText.isNullOrBlank()) {
          Text(
            text = itemCountText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      if (trailingAction != null) {
        trailingAction()
      } else if (onPlayAll != null && onShuffle == null) {
        Button(onClick = onPlayAll) {
          Icon(imageVector = Icons.RoundedFilled.PlayArrow, contentDescription = null)
          Spacer(modifier = Modifier.width(4.dp))
          Text(playButtonText)
        }
      }
    }

    if (onShuffle != null) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (onPlayAll != null) {
          Button(
            onClick = onPlayAll,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
          ) {
            Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(playButtonText)
          }
        }

        FilledTonalButton(
          onClick = onShuffle,
          modifier = if (onPlayAll != null) Modifier.weight(1f) else Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(12.dp),
        ) {
          Icon(Icons.RoundedFilled.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
          Spacer(Modifier.width(6.dp))
          Text("Shuffle")
        }
      }
    }
  }
}
