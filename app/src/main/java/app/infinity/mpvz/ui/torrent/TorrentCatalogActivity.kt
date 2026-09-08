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
import app.infinity.mpvz.ui.player.MpvInfinityTheme
import app.infinity.mpvz.utils.media.MediaUtils
import kotlinx.serialization.json.Json

/**
 * Transient resolver handoff for Stream discovery.
 *
 * This activity deliberately has no catalog/details UI. It resolves the first playable torrent
 * source and immediately opens the same TorrentSelectionActivity used by Network > Media.
 */
class TorrentCatalogActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val initialStreams =
      runCatching {
        Json.decodeFromString<List<StreamOption>>(
          intent.getStringExtra("streams_json").orEmpty(),
        )
      }.getOrDefault(emptyList())
    val seasons =
      runCatching {
        Json.decodeFromString<List<Season>>(intent.getStringExtra("seasons_json").orEmpty())
      }.getOrDefault(emptyList())
    val item =
      MediaItem(
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
    val catalogSettings = CatalogSettings(applicationContext)

    setContent {
      MpvInfinityTheme {
        ResolverHandoff(
          item = item,
          initialStreams = initialStreams,
          catalogSettings = catalogSettings,
          onResolved = ::openOriginalPicker,
        )
      }
    }
  }

  private fun openOriginalPicker(item: MediaItem, stream: StreamOption) {
    startActivity(
      Intent(this, TorrentSelectionActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(stream.url)
        putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, stream.url)
        putExtra(MediaUtils.EXTRA_MEDIA_TITLE, item.title)
        putExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION, item.overview)
        putExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL, item.posterUrl)
        putExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL, item.backdropUrl)
        putExtra("seasons_json", Json.encodeToString(item.seasons))
        intent.getIntExtra("episode_season", -1).takeIf { it >= 0 }?.let { putExtra("episode_season", it) }
        intent.getIntExtra("episode_number", -1).takeIf { it >= 0 }?.let { putExtra("episode_number", it) }
        intent.getStringExtra("episode_title")?.let { putExtra("episode_title", it) }
        intent.getStringExtra("episode_overview")?.let { putExtra("episode_overview", it) }
        intent.getStringExtra("episode_thumbnail")?.let { putExtra("episode_thumbnail", it) }
      },
    )
    finish()
    overridePendingTransition(0, 0)
  }
}

@Composable
private fun ResolverHandoff(
  item: MediaItem,
  initialStreams: List<StreamOption>,
  catalogSettings: CatalogSettings,
  onResolved: (MediaItem, StreamOption) -> Unit,
) {
  var error by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(item.providerId, item.imdbId, item.title, initialStreams) {
    val result =
      if (initialStreams.isNotEmpty()) {
        Result.success(initialStreams)
      } else {
        runCatching { CloudStreamResolver(catalogSettings).resolve(item) }
      }
    result
      .onSuccess { streams ->
        val selected =
          if (catalogSettings.autoChooseBestTorrent) {
            streams
              .filterNot(StreamOption::isPlayable)
              .maxWithOrNull(compareBy<StreamOption> { it.qualityRank }.thenBy { it.seeders })
              ?: streams.firstOrNull()
          } else {
            streams.firstOrNull()
          }
        if (selected != null) onResolved(item, selected) else error = "No torrent sources found for this title."
      }
      .onFailure { throwable -> error = throwable.message ?: "Unable to find torrents" }
  }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    if (error != null) {
      Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
    } else {
      CircularProgressIndicator()
    }
  }
}
