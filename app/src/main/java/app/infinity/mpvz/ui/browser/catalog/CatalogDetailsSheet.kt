package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.StreamOption
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import coil3.compose.AsyncImage

@Composable
fun CatalogDetailsPage(
  item: MediaItem,
  selectedSeason: Int?,
  streams: List<StreamOption>,
  isLoading: Boolean,
  error: String?,
  onBack: () -> Unit,
  onChooseSeason: (Int) -> Unit,
  onFindMovieStreams: () -> Unit,
  onChooseEpisode: (Int, Int) -> Unit,
  onPlay: (StreamOption) -> Unit,
) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
    Box(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))) {
      AsyncImage(item.backdropUrl ?: item.posterUrl, contentDescription = item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .24f), MaterialTheme.colorScheme.background.copy(alpha = .98f)))))
      Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          MetaPill(if (item.type == MediaType.TV) "SERIES" else "MOVIE")
          item.releaseYear?.let { MetaPill(it.take(12)) }
          item.contentRating?.takeIf { it.isNotBlank() }?.let { MetaPill("★ $it") }
          item.duration?.takeIf { it.isNotBlank() }?.let { MetaPill(it) }
        }
        Text(item.title, style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.genres.isNotEmpty()) Text(item.genres.take(4).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .88f), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }

    if (item.overview.isNotBlank()) Text(item.overview, Modifier.fillMaxWidth().padding(horizontal = 22.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (item.type == MediaType.MOVIE) {
      Button(onClick = onFindMovieStreams, enabled = !isLoading, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp), shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(vertical = 15.dp)) {
        if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(9.dp))
        Text(if (isLoading) "Finding direct streams…" else "Find direct streams", fontWeight = FontWeight.Bold)
      }
    } else {
      val seasons = item.seasons.sortedBy { it.number }
      if (seasons.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Episodes", Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.forEach { season ->
              FilterChip(selected = selectedSeason == season.number, onClick = { onChooseSeason(season.number) }, label = { Text("Season ${season.number}") })
            }
          }
          val activeSeason = seasons.firstOrNull { it.number == selectedSeason } ?: seasons.first()
          Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            activeSeason.episodes.sortedBy { it.number }.forEach { episode ->
              Column(Modifier.width(238.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled = !isLoading) { onChooseEpisode(activeSeason.number, episode.number) }) {
                  AsyncImage(episode.stillUrl ?: item.backdropUrl ?: item.posterUrl, contentDescription = episode.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                  Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .85f)))).padding(horizontal = 11.dp, vertical = 8.dp)) {
                    Text("S${activeSeason.number} · E${episode.number}", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                  }
                  Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = .58f)) {
                    Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Find episode streams", tint = Color.White, modifier = Modifier.padding(11.dp).size(25.dp))
                  }
                }
                Text(episode.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (episode.overview.isNotBlank()) Text(episode.overview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
              }
            }
          }
          if (activeSeason.episodes.isEmpty()) Text("No episode metadata is available for this season.", Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Episode metadata", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          Text(if (isLoading) "Loading episodes from the configured catalog metadata providers…" else "This title's catalog provider did not return episode metadata." , color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (isLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
      }
    }

    if (isLoading && streams.isEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
      Text(if (item.type == MediaType.TV) "Searching providers for this episode…" else "Searching providers…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let {
      Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onBack) { Text("Back to Stream") }
      }
    }
    val playable = streams.filter { it.url.startsWith("https://", true) && it.isPlayable && !it.isExternal }
    if (playable.isNotEmpty()) {
      Text("Direct streams", Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      playable.groupBy { it.source ?: "Nuvio provider" }.forEach { (source, rows) ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(source, Modifier.padding(horizontal = 22.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          rows.forEach { stream ->
            OutlinedButton(onClick = { onPlay(stream) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                  Text(stream.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                  val extra = listOfNotNull(stream.qualityRank.takeIf { it > 0 }?.let { "${it}p" }, stream.size, stream.filename).joinToString(" · ")
                  if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  if (stream.headers.isNotEmpty()) Text("Requires request headers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Play direct HTTPS stream", tint = MaterialTheme.colorScheme.primary)
              }
            }
          }
        }
      }
    }
  }
}

@Deprecated("StreamScreen now presents this content as a full-page destination")
@Composable
fun CatalogDetailsSheet(
  item: MediaItem,
  selectedSeason: Int?,
  streams: List<StreamOption>,
  isLoading: Boolean,
  error: String?,
  onDismiss: () -> Unit,
  onChooseSeason: (Int) -> Unit,
  onFindMovieStreams: () -> Unit,
  onChooseEpisode: (Int, Int) -> Unit,
  onPlay: (StreamOption) -> Unit,
) = CatalogDetailsPage(item, selectedSeason, streams, isLoading, error, onDismiss, onChooseSeason, onFindMovieStreams, onChooseEpisode, onPlay)

@Composable
private fun MetaPill(text: String) {
  Surface(color = Color.Black.copy(alpha = .56f), shape = RoundedCornerShape(8.dp)) {
    Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
  }
}
