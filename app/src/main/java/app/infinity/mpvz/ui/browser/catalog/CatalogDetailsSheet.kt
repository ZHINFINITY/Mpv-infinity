package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
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
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() },
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 760.dp)
        .verticalScroll(rememberScrollState())
        .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(260.dp)
          .padding(horizontal = 16.dp)
          .clip(RoundedCornerShape(22.dp)),
      ) {
        AsyncImage(
          model = item.backdropUrl ?: item.posterUrl,
          contentDescription = item.title,
          modifier = Modifier.matchParentSize(),
          contentScale = ContentScale.Crop,
        )
        Box(
          modifier = Modifier
            .matchParentSize()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .92f)))),
        )
        Column(
          modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = item.title,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MetadataTag(if (item.type == MediaType.TV) "SERIES" else "MOVIE")
            item.releaseYear?.let { MetadataTag(it) }
            item.contentRating?.let { MetadataTag("★ $it") }
          }
          if (item.genres.isNotEmpty()) {
            Text(item.genres.take(3).joinToString(" • "), color = Color.White.copy(alpha = .84f), style = MaterialTheme.typography.bodySmall)
          }
        }
      }

      if (item.overview.isNotBlank()) {
        Text(
          text = item.overview,
          modifier = Modifier.padding(horizontal = 20.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (item.type == MediaType.MOVIE) {
        Button(
          onClick = onFindMovieStreams,
          enabled = !isLoading,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
          contentPadding = PaddingValues(vertical = 14.dp),
          shape = RoundedCornerShape(18.dp),
        ) {
          if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          else Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(if (isLoading) "Finding secure streams…" else "Find streams")
        }
      } else {
        if (item.seasons.isNotEmpty()) {
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            item.seasons.forEach { season ->
              FilterChip(
                selected = selectedSeason == season.number,
                onClick = { onChooseSeason(season.number) },
                label = { Text("Season ${season.number}") },
              )
            }
          }
          val selectedSeasonData = item.seasons.firstOrNull { it.number == selectedSeason } ?: item.seasons.firstOrNull()
          selectedSeasonData?.episodes.orEmpty().forEach { episode ->
            OutlinedButton(
              onClick = { selectedSeasonData?.let { onChooseEpisode(it.number, episode.number) } },
              enabled = !isLoading,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
              shape = RoundedCornerShape(16.dp),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
              Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                  Text("${episode.number}. ${episode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                  if (episode.overview.isNotBlank()) {
                    Text(episode.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
                Spacer(Modifier.width(8.dp))
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Find streams")
              }
            }
          }
          if (item.seasons.all { it.episodes.isEmpty() }) {
            Text("No episode metadata is available from the configured add-ons.", modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        } else {
          Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (isLoading) "Loading episode metadata…" else "Episode metadata is not available from the configured add-ons.", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }

      error?.let {
        Text(it, modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
      }

      if (streams.isNotEmpty()) {
        Text("Direct HTTPS streams", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        streams.filter { it.url.startsWith("https://", ignoreCase = true) && it.isPlayable }.forEach { stream ->
          Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable { onPlay(stream) },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stream.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(stream.source, stream.qualityRank.takeIf { it > 0 }?.let { "${it}p" }, stream.mimeType).joinToString(" • ")
                if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Spacer(Modifier.width(12.dp))
              Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Play direct stream", tint = MaterialTheme.colorScheme.primary)
            }
          }
        }
      }
      Spacer(Modifier.height(6.dp))
    }
  }
}

@Composable
private fun MetadataTag(text: String) {
  Surface(color = Color.Black.copy(alpha = .48f), shape = RoundedCornerShape(7.dp)) {
    Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
  }
}
