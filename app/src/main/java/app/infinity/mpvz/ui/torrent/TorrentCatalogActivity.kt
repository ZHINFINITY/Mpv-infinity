package app.infinity.mpvz.ui.torrent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.infinity.mpvz.catalog.CatalogSettings
import app.infinity.mpvz.catalog.CloudStreamResolver
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.Season
import app.infinity.mpvz.catalog.StreamOption
import app.infinity.mpvz.ui.browser.catalog.MediaDetailsSheet
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import app.infinity.mpvz.utils.media.MediaUtils
import kotlinx.serialization.json.Json

/**
 * Direct resolver-driven source chooser. No torrent metadata or peer connection is started until
 * the user selects one of the resolver's torrent sources on this page.
 */
class TorrentCatalogActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val initialStreams = runCatching {
      Json.decodeFromString<List<StreamOption>>(intent.getStringExtra("streams_json").orEmpty())
    }.getOrDefault(emptyList())
    val seasons = runCatching {
      Json.decodeFromString<List<Season>>(intent.getStringExtra("seasons_json").orEmpty())
    }.getOrDefault(emptyList())
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
        ResolverChooser(
          item = item,
          initialStreams = initialStreams,
          catalogSettings = CatalogSettings(applicationContext),
          onTorrentSelected = ::openTorrentPicker,
          onBack = ::finishChooser,
        )
      }
    }
  }

  private fun openTorrentPicker(
    item: MediaItem,
    stream: StreamOption,
    season: Int?,
    episode: Int?,
  ) {
    startActivity(Intent(this, TorrentSelectionActivity::class.java).apply {
      action = Intent.ACTION_VIEW
      data = Uri.parse(stream.url)
      putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, stream.url)
      putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
      putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
      putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
      putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
      putExtra("seasons_json", Json.encodeToString(item.seasons))
      season?.let { putExtra("episode_season", it) }
      episode?.let { putExtra("episode_number", it) }
      item.seasons.firstOrNull { it.number == season }
        ?.episodes?.firstOrNull { it.number == episode }
        ?.let { selected ->
          putExtra("episode_title", selected.title)
          putExtra("episode_overview", selected.overview)
          putExtra("episode_thumbnail", selected.stillUrl)
        }
      stream.torrentFileIndex?.let { putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, it) }
    })
    finishChooser()
  }

  private fun finishChooser() {
    finish()
    overridePendingTransition(0, 0)
  }
}

@Composable
internal fun ResolverChooser(
  item: MediaItem,
  initialStreams: List<StreamOption>,
  catalogSettings: CatalogSettings,
  onTorrentSelected: (MediaItem, StreamOption, Int?, Int?) -> Unit,
  onBack: () -> Unit,
) {
  var streams by remember(initialStreams) { mutableStateOf(initialStreams) }
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var selectedSeason by remember { mutableStateOf<Int?>(null) }
  var selectedEpisode by remember { mutableStateOf<Int?>(null) }
  var sourceFilter by remember { mutableStateOf("All") }
  var sourceSort by remember { mutableStateOf("Best") }

  LaunchedEffect(item.providerId, item.imdbId, item.title, initialStreams, selectedSeason, selectedEpisode) {
    val isEpisodeRequest = selectedSeason != null && selectedEpisode != null
    if (item.type == MediaType.TV && !isEpisodeRequest) return@LaunchedEffect
    if (initialStreams.isNotEmpty() && !isEpisodeRequest) {
      streams = initialStreams
      return@LaunchedEffect
    }

    loading = true
    error = null
    runCatching { CloudStreamResolver(catalogSettings).resolve(item, selectedSeason, selectedEpisode) }
      .onSuccess {
        streams = it
        loading = false
        if (it.isEmpty()) error = "No torrent sources found for this selection."
      }
      .onFailure {
        loading = false
        error = it.message ?: "Unable to find torrent sources."
      }
  }

  if (error != null && streams.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
    }
    return
  }

  MediaDetailsSheet(
    item = item,
    streams = streams,
    sourceFilter = sourceFilter,
    sourceSort = sourceSort,
    isLoading = loading,
    onLoadSources = {},
    onEpisode = { season, episode ->
      selectedSeason = season
      selectedEpisode = episode
    },
    onFilter = { sourceFilter = it },
    onSort = { sourceSort = it },
    onSelect = { stream ->
      if (!stream.isPlayable) onTorrentSelected(item, stream, selectedSeason, selectedEpisode)
    },
    onBack = onBack,
  )
}
