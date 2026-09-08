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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.StreamOption
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.utils.media.MediaUtils
import kotlinx.serialization.json.Json

class TorrentCatalogActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val streams = runCatching { Json.decodeFromString<List<StreamOption>>(intent.getStringExtra("streams_json").orEmpty()) }.getOrDefault(emptyList())
    val item = MediaItem(
      id = 0,
      type = if (intent.getBooleanExtra("is_series", false)) app.infinity.mpvz.catalog.MediaType.TV else app.infinity.mpvz.catalog.MediaType.MOVIE,
      title = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_TITLE).orEmpty(),
      overview = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION).orEmpty(),
      posterUrl = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL),
      backdropUrl = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL),
    )
    setContent {
      MpvInfinityTheme {
        TorrentCatalogScreen(item, streams, intent.getStringExtra("seasons_json"), onBack = ::finish) { stream ->
        startActivity(Intent(this, if (stream.isPlayable) PlayerActivity::class.java else TorrentSelectionActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = Uri.parse(stream.url)
          putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, stream.url)
          putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
          putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
          putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
          putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
          putExtra("seasons_json", intent.getStringExtra("seasons_json"))
        })
        }
      }
    }
  }
}

@androidx.compose.runtime.Composable
private fun TorrentCatalogScreen(item: MediaItem, streams: List<StreamOption>, seasonsJson: String?, onBack: () -> Unit, onSelect: (StreamOption) -> Unit) {
  val episodePattern = Regex("(?i)\\bS(\\d{1,2})[ ._-]*E(\\d{1,4})\\b")
  var quality by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("All") }
  var sort by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Best") }
  var season by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
  val catalogSeasons = runCatching { Json.decodeFromString<List<app.infinity.mpvz.catalog.Season>>(seasonsJson.orEmpty()) }.getOrDefault(emptyList())
  val seasons = (catalogSeasons.map { it.number } + streams.mapNotNull { episodePattern.find(it.title)?.groupValues?.getOrNull(1)?.toIntOrNull() }).distinct().sorted()
  val visible = streams.filter { quality == "All" || (quality == "4K" && it.qualityRank >= 2160) || (quality == "1080p" && it.qualityRank == 1080) || (quality == "720p" && it.qualityRank == 720) }.filter { season == null || episodePattern.find(it.title)?.groupValues?.getOrNull(1)?.toIntOrNull() == season }.let { list -> when (sort) { "Seeders" -> list.sortedByDescending { it.seeders }; "Size" -> list.sortedByDescending { it.size?.filter(Char::isDigit)?.toLongOrNull() ?: 0L }; "Quality" -> list.sortedByDescending { it.qualityRank }; else -> list.sortedWith(compareByDescending<StreamOption> { it.qualityRank }.thenByDescending { it.seeders }) } }
  androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
    androidx.compose.foundation.layout.Column {
      androidx.compose.material3.TopAppBar(title = { androidx.compose.material3.Text("Choose what to play") }, navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { app.infinity.mpvz.ui.icons.Icon(app.infinity.mpvz.ui.icons.Icons.RoundedFilled.ArrowBack, "Back") } })
      androidx.compose.foundation.lazy.LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        item { androidx.compose.material3.Text(item.title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall) }
        if (seasons.isNotEmpty()) item { androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { item { androidx.compose.material3.FilterChip(selected = season == null, onClick = { season = null }, label = { androidx.compose.material3.Text("All seasons") }) }; items(seasons) { value -> androidx.compose.material3.FilterChip(selected = season == value, onClick = { season = value }, label = { androidx.compose.material3.Text("Season $value") }) } } }
        item { androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { listOf("All", "4K", "1080p", "720p").forEach { value -> item { androidx.compose.material3.FilterChip(selected = quality == value, onClick = { quality = value }, label = { androidx.compose.material3.Text(value) }) } }; listOf("Best", "Quality", "Seeders", "Size").forEach { value -> item { androidx.compose.material3.FilterChip(selected = sort == value, onClick = { sort = value }, label = { androidx.compose.material3.Text(value) }) } } } }
        visible.groupBy { episodePattern.find(it.title)?.value ?: "Other sources" }.forEach { (episodeLabel, episodeStreams) ->
          item { androidx.compose.material3.Text(episodeLabel, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
          items(episodeStreams, key = { it.url }) { stream ->
            androidx.compose.material3.Card(modifier = androidx.compose.ui.Modifier.fillMaxWidth().clickable { onSelect(stream) }) { androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.padding(14.dp)) { androidx.compose.material3.Text(stream.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium); androidx.compose.material3.Text(listOfNotNull(stream.qualityRank.takeIf { it > 0 }?.let { "${it}p" }, stream.size, stream.seeders.takeIf { it > 0 }?.let { "$it seeders" }, stream.source).joinToString(" • "), style = androidx.compose.material3.MaterialTheme.typography.bodySmall) } }
          }
        }
      }
    }
  }
}
