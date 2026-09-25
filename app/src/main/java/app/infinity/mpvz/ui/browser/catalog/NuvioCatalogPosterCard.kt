package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.MediaItem
import coil3.compose.AsyncImage

/** Nuvio HomePosterCard-style portrait poster with title and release information below. */
@Composable
fun NuvioCatalogPosterCard(item: MediaItem, resolving: Boolean = false, onClick: () -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  Column(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      item.posterUrl?.let { image ->
        AsyncImage(
          model = image,
          contentDescription = item.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } ?: Text(
        text = item.title,
        modifier = Modifier.padding(12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
      if (resolving) CircularProgressIndicator(Modifier.align(Alignment.Center).padding(8.dp), strokeWidth = 2.dp)
    }
    Text(
      text = item.title,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    item.releaseYear?.takeIf(String::isNotBlank)?.let { releaseInfo ->
      Text(
        text = releaseInfo.take(12),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
