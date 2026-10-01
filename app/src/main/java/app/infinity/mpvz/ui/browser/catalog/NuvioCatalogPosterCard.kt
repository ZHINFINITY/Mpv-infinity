package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.catalogArtworkCandidates
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.presentation.components.RemoteImageWithFallback

/** Compact cinematic poster card used by Stream rails and search grids. */
@Composable
fun NuvioCatalogPosterCard(
  item: MediaItem,
  resolving: Boolean = false,
  onPosterLoadFailure: () -> Unit = {},
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(16.dp)
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(140), label = "poster-press")
  val lift by animateFloatAsState(if (pressed) 2f else 0f, tween(140), label = "poster-lift")

  Column(
    modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; translationY = -lift },
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f)
        .shadow(14.dp, shape, clip = false)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      val historyEpisode = item.historyEpisode?.let { episodeNumber ->
        item.seasons.firstOrNull { it.number == item.historySeason }?.episodes?.firstOrNull { it.number == episodeNumber }
      }
      val historyImage = item.historyStillUrl?.takeIf(String::isNotBlank)
        ?: historyEpisode?.stillUrl?.takeIf(String::isNotBlank)
      val posterImage = item.posterUrl?.takeIf(String::isNotBlank)
      when {
        historyImage != null -> RemoteImage(
          url = historyImage,
          contentDescription = item.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        posterImage != null -> RemoteImageWithFallback(
          urls = catalogArtworkCandidates(listOf(posterImage)),
          contentDescription = item.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
          onAllCandidatesFailed = onPosterLoadFailure,
        )
        else -> Text(
          text = item.title,
          modifier = Modifier.padding(12.dp),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Box(
        Modifier.fillMaxSize().background(
          Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .84f))),
        ),
      )
      Box(
        Modifier.fillMaxWidth().height(72.dp).align(Alignment.TopStart).background(
          Brush.verticalGradient(listOf(Color.Black.copy(alpha = .32f), Color.Transparent)),
        ),
      )
      Surface(
        modifier = Modifier.align(Alignment.TopStart).padding(9.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .78f),
        contentColor = MaterialTheme.colorScheme.onSurface,
      ) {
        Text(
          if (item.type == MediaType.TV) "TV" else "MOVIE",
          Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Black,
        )
      }
      Text(
        if (historyEpisode != null) "${item.title} · E${historyEpisode.number}" else item.title,
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(11.dp),
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (resolving) CircularProgressIndicator(Modifier.align(Alignment.Center).padding(8.dp), strokeWidth = 2.dp)
      if (item.historyProgress > 0f) {
        Box(
          Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
            .background(Color.White.copy(alpha = .25f)),
        )
        Box(
          Modifier.align(Alignment.BottomStart).fillMaxWidth(item.historyProgress).height(4.dp)
            .background(MaterialTheme.colorScheme.primary),
        )
      }
    }
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 3.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (item.type == MediaType.TV) "Series" else "Movie",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
      )
      item.releaseYear?.takeIf(String::isNotBlank)?.let { releaseInfo ->
        Text("· ${releaseInfo.take(12)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
