package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

@Composable
fun CatalogGridItem(item: MediaItem, resolving: Boolean = false, onClick: () -> Unit) {
  val posterShape = RoundedCornerShape(14.dp)
  Column(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f)
        .clip(posterShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      item.posterUrl?.let { poster ->
        AsyncImage(
          model = poster,
          contentDescription = item.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      }
      if (resolving) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).padding(8.dp), strokeWidth = 2.dp)
    }
    Text(
      text = item.title,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    item.releaseYear?.takeIf { it.isNotBlank() }?.let { year ->
      Text(
        text = year,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
