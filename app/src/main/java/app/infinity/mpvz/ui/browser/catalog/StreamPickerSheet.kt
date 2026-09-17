package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.StreamOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPickerSheet(
  streams: List<StreamOption>,
  loading: Boolean,
  error: String?,
  sourceFilter: String,
  sourceSort: String,
  onFilter: (String) -> Unit,
  onSort: (String) -> Unit,
  onSelect: (StreamOption) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Choose a source", style = MaterialTheme.typography.headlineSmall)
      Text("Ranked by quality, availability, and seeders", style = MaterialTheme.typography.bodySmall)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("All", "WatchHub", "Torrentio").forEach { filter -> FilterChip(selected = sourceFilter == filter, onClick = { onFilter(filter) }, label = { Text(filter) }) }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Best", "Quality", "Seeders", "Size").forEach { sort -> FilterChip(selected = sourceSort == sort, onClick = { onSort(sort) }, label = { Text(sort) }) }
      }
      when {
        loading -> repeat(4) { StreamSkeleton() }
        error != null -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 24.dp))
        streams.isEmpty() -> Text("No playable sources were returned.", modifier = Modifier.padding(vertical = 24.dp))
        else -> {
          val visible = streams.filter { sourceFilter == "All" || (sourceFilter == "Torrentio" && !it.isPlayable) || (sourceFilter == "WatchHub" && it.isPlayable) }.let { source ->
            when (sourceSort) {
              "Quality" -> source.sortedByDescending { it.qualityRank }
              "Seeders" -> source.sortedByDescending { it.seeders }
              "Size" -> source.sortedByDescending { it.size?.filter(Char::isDigit)?.toLongOrNull() ?: 0L }
              else -> source.sortedWith(compareByDescending<StreamOption> { it.isPlayable }.thenByDescending { it.qualityRank }.thenByDescending { it.seeders })
            }
          }
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.url }) { stream ->
              Card(Modifier.fillMaxWidth().clickable { onSelect(stream) }) {
                Column(Modifier.padding(14.dp)) {
                  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stream.qualityRank.takeIf { it > 0 }?.let { "${it}p" } ?: "SOURCE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(stream.title, style = MaterialTheme.typography.titleMedium)
                  }
                  Text(listOfNotNull(stream.size, stream.seeders.takeIf { it > 0 }?.let { "$it seeders" }, stream.source).joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                  Text(if (stream.isPlayable) "Play now" else "Stream torrent", style = MaterialTheme.typography.labelLarge)
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StreamSkeleton() {
  val transition = rememberInfiniteTransition(label = "streamSkeleton")
  val alpha = transition.animateFloat(.35f, .8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeletonAlpha")
  Box(Modifier.fillMaxWidth().height(72.dp).graphicsLayer { this.alpha = alpha.value }.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}
