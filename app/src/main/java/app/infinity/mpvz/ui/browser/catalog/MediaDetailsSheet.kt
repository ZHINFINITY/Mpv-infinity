package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.StreamOption
import coil3.compose.AsyncImage

@Composable
fun MediaDetailsSheet(
  item: MediaItem,
  streams: List<StreamOption>,
  sourceFilter: String,
  sourceSort: String,
  isLoading: Boolean,
  onLoadSources: () -> Unit,
  onEpisode: (Int, Int) -> Unit,
  onFilter: (String) -> Unit,
  onSort: (String) -> Unit,
  onSelect: (StreamOption) -> Unit,
  onBack: () -> Unit,
) {
  var activeSeason by remember(item.id) { mutableStateOf(item.seasons.firstOrNull()?.number) }
  var expandedSynopsis by remember(item.id) { mutableStateOf(false) }
  LaunchedEffect(item.id) { onLoadSources() }
  Column(
    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 112.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
      TextButton(onClick = onBack) { Text("‹ Back") }
      Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    AsyncImage(model = item.backdropUrl ?: item.posterUrl, contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(230.dp), contentScale = ContentScale.Crop)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(item.title, style = MaterialTheme.typography.headlineMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOfNotNull(item.releaseYear, item.contentRating, item.duration, item.genres.firstOrNull()).forEach { AssistChip(onClick = {}, label = { Text(it) }) }
      }
      if (item.overview.isNotBlank()) {
        Text(item.overview, style = MaterialTheme.typography.bodyLarge, maxLines = if (expandedSynopsis) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { expandedSynopsis = !expandedSynopsis }) { Text(if (expandedSynopsis) "Show less" else "Read more") }
      }
      if (item.type == app.infinity.mpvz.catalog.MediaType.TV && item.seasons.isNotEmpty()) {
        Text("Seasons and episodes", style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(item.seasons) { season ->
            FilterChip(selected = activeSeason == season.number, onClick = { activeSeason = season.number }, label = { Text("Season ${season.number}") })
          }
        }
        item.seasons.firstOrNull { it.number == activeSeason }?.let { season ->
          season.episodes.forEach { episode ->
            Card(Modifier.fillMaxWidth().clickable { onEpisode(season.number, episode.number) }) {
              Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = episode.stillUrl, contentDescription = episode.title, modifier = Modifier.size(110.dp, 62.dp), contentScale = ContentScale.Crop)
                Column(Modifier.padding(start = 10.dp)) {
                  Text("${episode.number}. ${episode.title}", style = MaterialTheme.typography.titleSmall)
                  Text(episode.overview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
        }
      }
      Text("Sources", style = MaterialTheme.typography.titleLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("All", "WatchHub", "Torrentio").forEach { filter -> TextButton(onClick = { onFilter(filter) }) { Text(if (filter == sourceFilter) "● $filter" else filter) } }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Best", "Quality", "Seeders", "Size").forEach { sort ->
          FilterChip(selected = sourceSort == sort, onClick = { onSort(sort) }, label = { Text(sort) })
        }
      }
      val visibleStreams = streams.filter { sourceFilter == "All" || (sourceFilter == "Torrentio" && !it.isPlayable) || (sourceFilter == "WatchHub" && it.isPlayable) }.let { source ->
        when (sourceSort) {
          "Quality" -> source.sortedByDescending { it.qualityRank }
          "Seeders" -> source.sortedByDescending { it.seeders }
          "Size" -> source.sortedByDescending { it.size?.filter(Char::isDigit)?.toLongOrNull() ?: 0L }
          else -> source.sortedWith(compareByDescending<StreamOption> { it.isPlayable }.thenByDescending { it.qualityRank }.thenByDescending { it.seeders })
        }
      }
      visibleStreams.forEach { stream ->
        val metadata = listOfNotNull(stream.qualityRank.takeIf { it > 0 }?.let { "${it}p" }, stream.seeders.takeIf { it > 0 }?.let { "$it seeders" }, stream.size, stream.source).joinToString(" • ")
        Card(Modifier.fillMaxWidth().clickable { onSelect(stream) }) {
          Column(Modifier.padding(14.dp)) {
            Text(stream.title, style = MaterialTheme.typography.titleMedium)
            if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(if (stream.isPlayable) "Play source" else "Stream torrent", style = MaterialTheme.typography.labelLarge)
          }
        }
      }
      if (streams.isEmpty() && !isLoading && item.type == app.infinity.mpvz.catalog.MediaType.TV) Text("Select an episode to load sources.", style = MaterialTheme.typography.bodyMedium)
    }
  }
}
