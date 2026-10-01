package app.infinity.mpvz.ui.browser.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.Episode
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.Season
import app.infinity.mpvz.catalog.canResolveCatalogItemWithoutEpisodes
import app.infinity.mpvz.catalog.highQualityPosterUrl
import app.infinity.mpvz.ui.browser.LocalNavigationBarHeight
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.presentation.components.RemoteImage
import coil3.compose.AsyncImage

@Composable
fun CatalogDetailsPage(
  item: MediaItem,
  selectedSeason: Int?,
  isMetadataLoading: Boolean,
  isResolving: Boolean,
  error: String?,
  onBack: () -> Unit,
  onChooseSeason: (Int) -> Unit,
  onFindDirectStreams: () -> Unit,
  onChooseEpisode: (Int, Episode) -> Unit,
) {
  val seasons = item.seasons.sortedBy { it.number }
  val activeSeason = seasons.firstOrNull { it.number == selectedSeason } ?: seasons.firstOrNull()
  val posterScale by animateFloatAsState(if (isResolving) .96f else 1f, tween(240), label = "detail-poster-scale")
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalNavigationBarHeight.current + 36.dp),
    verticalArrangement = Arrangement.spacedBy(22.dp),
  ) {
    Box(Modifier.fillMaxWidth().heightIn(min = 270.dp, max = 300.dp).clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))) {
      AsyncImage(
        model = item.backdropUrl ?: item.posterUrl,
        contentDescription = item.title,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
      item.posterUrl?.let { poster ->
        AsyncImage(
          model = highQualityPosterUrl(poster),
          contentDescription = "${item.title} poster",
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 18.dp, end = 18.dp)
            .width(94.dp)
            .height(138.dp)
            .shadow(14.dp, RoundedCornerShape(14.dp), clip = false)
            .clip(RoundedCornerShape(14.dp))
            .graphicsLayer { scaleX = posterScale; scaleY = posterScale },
          contentScale = ContentScale.Crop,
        )
      }
      Box(
        Modifier.fillMaxSize().background(
          Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .18f), Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .55f), MaterialTheme.colorScheme.background),
          ),
        ),
      )
      Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          MetaPill(if (item.type == MediaType.TV) "SERIES" else "MOVIE")
          item.releaseYear?.takeIf(String::isNotBlank)?.let { MetaPill(it.take(12)) }
          item.contentRating?.takeIf(String::isNotBlank)?.let { MetaPill("★ $it") }
          item.duration?.takeIf(String::isNotBlank)?.let { MetaPill(it) }
        }
        Text(
          item.title,
          style = MaterialTheme.typography.headlineLarge,
          color = Color.White,
          fontWeight = FontWeight.Black,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (item.genres.isNotEmpty()) {
          Text(item.genres.take(4).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.type == MediaType.MOVIE) {
          Button(
            onClick = onFindDirectStreams,
            enabled = !isMetadataLoading && !isResolving,
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
          ) {
            if (isResolving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isResolving) "Finding links" else "Play", fontWeight = FontWeight.Bold)
          }
        } else if (canResolveCatalogItemWithoutEpisodes(item)) {
          Button(
            onClick = onFindDirectStreams,
            enabled = !isMetadataLoading && !isResolving,
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
          ) {
            if (isResolving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isResolving) "Finding links" else "Play", fontWeight = FontWeight.Bold)
          }
        }
      }
    }

    if (item.overview.isNotBlank()) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(item.overview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    if (item.type == MediaType.TV) {
      if (seasons.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
              Text("Seasons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
              Text("Choose a season to browse episodes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            activeSeason?.let { Text("${seasons.size} seasons", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          }
          LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(seasons, key = { "${it.number}:${it.posterUrl.orEmpty()}" }) { season ->
                SeasonPosterCard(
                  seriesPosterUrl = item.posterUrl,
                  season = season,
                selected = season.number == activeSeason?.number,
                onClick = { onChooseSeason(season.number) },
              )
            }
          }
          activeSeason?.let { season ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
              Text("Season ${season.number}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
              Text("${season.episodes.size} episodes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (season.episodes.isEmpty()) {
              Text("No episode metadata is available for this season.", Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              LazyRow(
                contentPadding = PaddingValues(horizontal = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                items(season.episodes.sortedBy { it.number }, key = { "${season.number}:${it.number}" }) { episode ->
                  EpisodeLandscapeCard(
                    item = item,
                    season = season.number,
                    episode = episode,
                    enabled = !isMetadataLoading && !isResolving,
                    onClick = { onChooseEpisode(season.number, episode) },
                  )
                }
              }
            }
          }
        }
      } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
          if (isMetadataLoading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Loading season and episode details…", color = MaterialTheme.colorScheme.onSurfaceVariant)
          } else {
            Text("No season data was returned by this title's catalog metadata provider.", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    error?.let { message ->
      Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onBack) { Text("Back to titles") }
      }
    }
  }
}

@Composable
private fun SeasonPosterCard(seriesPosterUrl: String?, season: Season, selected: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(14.dp)
  val posterScale by animateFloatAsState(if (selected) 1.02f else 1f, tween(220), label = "season-poster-scale")
  val borderColor by animateColorAsState(
    if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .12f),
    animationSpec = tween(220),
    label = "season-border",
  )
  Column(Modifier.width(116.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(
              Modifier.fillMaxWidth().height(174.dp).graphicsLayer { scaleX = posterScale; scaleY = posterScale }.shadow(16.dp, shape, clip = false).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .border(if (selected) 2.dp else 1.dp, borderColor, shape)
        .clickable(onClick = onClick),
    ) {
      val posterUrl = season.posterUrl?.let(::highQualityPosterUrl)
        ?: seriesPosterUrl?.let(::highQualityPosterUrl)
      if (posterUrl != null) {
        RemoteImage(
          url = posterUrl,
          contentDescription = "Season ${season.number}",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceVariant)))) {
        Text("S${season.number}", Modifier.align(Alignment.Center), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Black)
      }
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)))))
      Column(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("SEASON ${season.number}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .85f), fontWeight = FontWeight.Bold)
        Text("${season.episodes.size} episodes", style = MaterialTheme.typography.labelMedium, color = Color.White)
      }
    }
    Text("Season ${season.number}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun EpisodeLandscapeCard(
  item: MediaItem,
  season: Int,
  episode: app.infinity.mpvz.catalog.Episode,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(15.dp)
  Box(
    Modifier.width(280.dp).height(124.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled = enabled, onClick = onClick),
  ) {
    AsyncImage(
      model = episode.stillUrl ?: item.backdropUrl ?: item.posterUrl,
      contentDescription = episode.title,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f)))))
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 13.dp, end = 54.dp, top = 36.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("S${season.toString().padStart(2, '0')} · E${episode.number.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .86f), fontWeight = FontWeight.SemiBold)
      Text(episode.title, style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (episode.overview.isNotBlank()) Text(episode.overview, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .88f), maxLines = 2, overflow = TextOverflow.Ellipsis)
      episode.runtime?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .72f)) }
    }
    Surface(Modifier.align(Alignment.Center), shape = CircleShape, color = Color.Black.copy(alpha = .56f)) {
      Icon(Icons.RoundedFilled.PlayArrow, contentDescription = "Find episode streams", tint = Color.White, modifier = Modifier.padding(10.dp).size(25.dp))
    }
    if (!enabled) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .24f)), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
    }
  }
}

@Composable
private fun MetaPill(text: String) {
  Surface(color = Color.Black.copy(alpha = .54f), shape = RoundedCornerShape(9.dp)) {
    Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
  }
}
