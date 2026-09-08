package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.ui.player.components.expressive.ExpressiveElevatedCard
import coil3.compose.AsyncImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun CatalogGridItem(item: MediaItem, resolving: Boolean, onClick: () -> Unit) {
  ExpressiveElevatedCard(
    modifier = Modifier.fillMaxWidth().animateContentSize(),
    onClick = onClick,
  ) {
    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp))) {
      AsyncImage(
        model = item.posterUrl,
        contentDescription = item.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6090A0F)))))
      Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (resolving) "Resolving…" else "${item.provider.name} • ${item.type.name}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .8f))
      }
    }
  }
}
