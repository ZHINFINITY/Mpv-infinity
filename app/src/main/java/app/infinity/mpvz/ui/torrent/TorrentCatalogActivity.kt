package app.infinity.mpvz.ui.torrent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.catalog.CatalogSettings
import app.infinity.mpvz.catalog.CloudStreamResolver
import app.infinity.mpvz.catalog.Episode
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.Season
import app.infinity.mpvz.catalog.StreamOption
import app.infinity.mpvz.presentation.components.RemoteImage
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import app.infinity.mpvz.utils.media.MediaUtils
import kotlinx.serialization.json.Json

class TorrentCatalogActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val streams = runCatching { Json.decodeFromString<List<StreamOption>>(intent.getStringExtra("streams_json").orEmpty()) }.getOrDefault(emptyList())
    val seasonsJson = intent.getStringExtra("seasons_json")
    val seasons = runCatching { Json.decodeFromString<List<Season>>(seasonsJson.orEmpty()) }.getOrDefault(emptyList())
    val item = MediaItem(
      id = 0,
      type = if (intent.getBooleanExtra("is_series", false)) MediaType.TV else MediaType.MOVIE,
      title = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_TITLE).orEmpty(),
      overview = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION).orEmpty(),
      posterUrl = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL),
      backdropUrl = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL),
      imdbId = intent.getStringExtra("catalog_imdb_id"),
      providerId = intent.getStringExtra("catalog_provider_id"),
      releaseYear = intent.getStringExtra("catalog_release_year"),
      contentRating = intent.getStringExtra("catalog_rating"),
      duration = intent.getStringExtra("catalog_duration"),
      genres = intent.getStringExtra("catalog_genres")?.split(" • ").orEmpty(),
      seasons = seasons,
    )
    setContent {
      MpvInfinityTheme {
        var resolvedStreams by remember { mutableStateOf(streams) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(streams.isEmpty()) }
        LaunchedEffect(item.providerId, item.imdbId, item.title) {
          if (streams.isEmpty()) {
            runCatching { CloudStreamResolver(CatalogSettings(applicationContext)).resolve(item) }
              .onSuccess { resolvedStreams = it }
              .onFailure { error = it.message ?: "Unable to find torrents" }
            loading = false
          }
        }
        TorrentCatalogScreen(item, resolvedStreams, loading, error, onBack = ::finish) { stream ->
          startActivity(Intent(this, if (stream.isPlayable) PlayerActivity::class.java else TorrentSelectionActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(stream.url)
            putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, stream.url)
            putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
            putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
            putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
            putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
            putExtra("seasons_json", Json.encodeToString(item.seasons))
          })
        }
      }
    }
  }
}

private data class EpisodeKey(val season: Int?, val episode: Int?)

@Composable
private fun TorrentCatalogScreen(item: MediaItem, streams: List<StreamOption>, loading: Boolean, error: String?, onBack: () -> Unit, onSelect: (StreamOption) -> Unit) {
  val episodePattern = remember { Regex("(?i)\\bS(\\d{1,2})[ ._-]*E(\\d{1,4})\\b") }
  var quality by remember { mutableStateOf("All") }
  var sort by remember { mutableStateOf("Best") }
  var selectedSeason by remember { mutableStateOf<Int?>(null) }
  val seasons = remember(item.seasons, streams) {
    (item.seasons.map { it.number } + streams.mapNotNull { episodePattern.find(it.title)?.groupValues?.getOrNull(1)?.toIntOrNull() }).distinct().sorted()
  }
  val filtered = streams.filter { stream ->
    (quality == "All" || (quality == "4K" && stream.qualityRank >= 2160) || (quality == "1080p" && stream.qualityRank == 1080) || (quality == "720p" && stream.qualityRank == 720)) &&
      (selectedSeason == null || episodePattern.find(stream.title)?.groupValues?.getOrNull(1)?.toIntOrNull() == selectedSeason)
  }
  val sorted = when (sort) {
    "Quality" -> filtered.sortedByDescending { it.qualityRank }
    "Seeders" -> filtered.sortedByDescending { it.seeders }
    "Size" -> filtered.sortedByDescending { it.size?.filter(Char::isDigit)?.toLongOrNull() ?: 0L }
    else -> filtered.sortedWith(compareByDescending<StreamOption> { it.qualityRank }.thenByDescending { it.seeders })
  }
  val streamGroups = sorted.groupBy { match ->
    episodePattern.find(match.title)?.let { EpisodeKey(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull()) } ?: EpisodeKey(null, null)
  }
  val episodeKeys = (item.seasons.flatMap { season -> season.episodes.map { EpisodeKey(season.number, it.number) } } + streamGroups.keys).distinct().filter { selectedSeason == null || it.season == selectedSeason }.sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column {
      TopAppBar(title = { Text("Choose what to play") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.RoundedFilled.ArrowBack, "Back") } })
      LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { MediaHero(item) }
        if (loading) item { CircularProgressIndicator(modifier = Modifier.padding(horizontal = 20.dp)) }
        if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        if (seasons.isNotEmpty()) item { SeasonChips(seasons, selectedSeason) { selectedSeason = it } }
        item { FilterChips(quality, sort, { quality = it }, { sort = it }) }
        episodeKeys.forEach { key ->
          val sourceList = streamGroups[key].orEmpty()
          item {
            val episode = key.season?.let { season -> key.episode?.let { number -> item.seasons.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == number } } }
            EpisodeHeader(key, episode)
          }
          items(sourceList, key = { it.url }) { stream -> TorrentSourceCard(stream, onSelect) }
        }
        if (!loading && streamGroups.isEmpty()) item { Text("No torrent sources found for this title.", modifier = Modifier.padding(20.dp)) }
      }
    }
  }
}

@Composable private fun MediaHero(item: MediaItem) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item.backdropUrl?.let { RemoteImage(it, item.title, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)), ContentScale.Crop) }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
      item.posterUrl?.let { RemoteImage(it, item.title, Modifier.width(112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)), ContentScale.Crop) }
      Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
        Text(item.title, style = MaterialTheme.typography.headlineSmall)
        val tags = listOfNotNull(item.releaseYear, item.contentRating, item.duration).filter { it.isNotBlank() } + item.genres.take(3)
        if (tags.isNotEmpty()) Text(tags.joinToString(" • "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (item.type == MediaType.TV) Text("${item.seasons.size} seasons", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (item.overview.isNotBlank()) Text(item.overview, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
      }
    }
  }
}

@Composable private fun SeasonChips(seasons: List<Int>, selected: Int?, onSelected: (Int?) -> Unit) {
  LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    item { FilterChip(selected == null, { onSelected(null) }, label = { Text("All seasons") }) }
    items(seasons) { season -> FilterChip(selected == season, { onSelected(season) }, label = { Text("Season $season") }) }
  }
}

@Composable private fun FilterChips(quality: String, sort: String, onQuality: (String) -> Unit, onSort: (String) -> Unit) {
  LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf("All", "4K", "1080p", "720p").forEach { value -> item { FilterChip(quality == value, { onQuality(value) }, label = { Text(value) }) } }
    listOf("Best", "Quality", "Seeders", "Size").forEach { value -> item { FilterChip(sort == value, { onSort(value) }, label = { Text(value) }) } }
  }
}

@Composable private fun EpisodeHeader(key: EpisodeKey, episode: Episode?) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    episode?.stillUrl?.let { RemoteImage(it, episode.title, Modifier.size(92.dp, 54.dp).clip(RoundedCornerShape(8.dp)), ContentScale.Crop) }
    Column {
      Text(if (key.season != null && key.episode != null) "Season ${key.season} • Episode ${key.episode}" else "Other sources", style = MaterialTheme.typography.titleMedium)
      episode?.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
  }
}

@Composable private fun TorrentSourceCard(stream: StreamOption, onSelect: (StreamOption) -> Unit) {
  Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onSelect(stream) }) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Text(stream.title, style = MaterialTheme.typography.titleMedium, maxLines = 3)
      Text(listOfNotNull(stream.qualityRank.takeIf { it > 0 }?.let { if (it >= 2160) "4K" else "${it}p" }, stream.size, stream.seeders.takeIf { it > 0 }?.let { "$it seeders" }, stream.source).joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}
