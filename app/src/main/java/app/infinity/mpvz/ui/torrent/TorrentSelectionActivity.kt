/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.torrent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.infinity.mpvz.catalog.CloudStreamResolver
import app.infinity.mpvz.catalog.CinemetaCatalogRepository
import app.infinity.mpvz.catalog.Episode
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.CatalogProvider
import app.infinity.mpvz.catalog.Season
import app.infinity.mpvz.catalog.StreamOption
import app.infinity.mpvz.database.repository.NetworkStreamEntryRepository
import app.infinity.mpvz.domain.torrent.TorrentStreamingEngine
import app.infinity.mpvz.repository.wyzie.WyzieSearchRepository
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.theme.MpvInfinityTheme
import app.infinity.mpvz.utils.media.MediaUtils
import kotlinx.serialization.json.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koin.android.ext.android.inject

class TorrentSelectionActivity : AppCompatActivity() {
  private companion object { const val DIAG_TAG = "MpvCatalogDiag" }
  private val torrentStreamingEngine: TorrentStreamingEngine by inject()
  private val streamEntryRepository: NetworkStreamEntryRepository by inject()
  private val wyzieSearchRepository: WyzieSearchRepository by inject()
  private val viewModel: TorrentSelectionViewModel by viewModels {
    TorrentSelectionViewModel.factory(
      torrentStreamingEngine = torrentStreamingEngine,
      streamEntryRepository = streamEntryRepository,
      wyzieSearchRepository = wyzieSearchRepository,
    )
  }

  private var playerLaunched = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val source = extractTorrentSource(intent)
    Log.i(DIAG_TAG, "selection entry sourceKind=${source?.let { if (it.startsWith("http")) "http" else if (it.startsWith("magnet")) "magnet" else "other" } ?: "none"} hasResolverItem=${source.isNullOrBlank() && intent.hasExtra("catalog_provider_id")}")
    if (source.isDirectPlayableUrl()) {
      Log.i(DIAG_TAG, "direct playback dispatch urlHost=${runCatching { Uri.parse(source).host }.getOrNull()}")
      startActivity(Intent(this, PlayerActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(source)
        putExtra(Intent.EXTRA_STREAM, Uri.parse(source))
        putExtra(MediaUtils.EXTRA_MEDIA_TITLE, intent.getStringExtra(MediaUtils.EXTRA_MEDIA_TITLE).orEmpty())
        putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, source)
      })
      finishWithoutAnimation()
      return
    }
    val resolverItem = if (source.isNullOrBlank()) resolverMediaItem(intent) else null
    if (source.isNullOrBlank() && resolverItem == null) {
      finishWithoutAnimation()
      return
    }

    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = closePicker()
      },
    )

    source?.let { viewModel.initialize(torrentInput(it, intent)) }

    setContent {
      val state by viewModel.uiState.collectAsState()
      LaunchedEffect(viewModel) { viewModel.launches.collect(::openPlayer) }
      MpvInfinityTheme {
        LaunchedEffect(resolverItem) {
          if (resolverItem != null && source.isNullOrBlank()) {
            val resolver = CloudStreamResolver(app.infinity.mpvz.catalog.CatalogSettings(applicationContext))
            val completeSeasons = if (resolverItem.type == MediaType.TV && !resolverItem.providerId.isNullOrBlank()) {
              (resolverItem.seasons + runCatching { CinemetaCatalogRepository().seasons(resolverItem.providerId) }.getOrDefault(emptyList()))
                .groupBy { it.number }
                .map { (number, seasons) -> Season(number, seasons.flatMap { it.episodes }.distinctBy { it.number }.sortedBy { it.number }) }
                .sortedBy { it.number }
            } else {
              resolverItem.seasons
            }
            val completeItem = resolverItem.copy(seasons = completeSeasons)
            val resolvedStreams = if (completeItem.type == MediaType.MOVIE) {
              resolver.resolve(completeItem, null, null)
            } else {
              val broadResults = runCatching { resolver.resolve(completeItem, null, null) }.getOrDefault(emptyList())
              val knownSeasonResults = completeItem.seasons.flatMap { season ->
                season.episodes.flatMap { episode ->
                  runCatching { resolver.resolve(completeItem, season.number, episode.number) }.getOrDefault(emptyList()).map {
                    it.copy(season = season.number, episode = episode.number)
                  }
                }
              }
              val discoveredSeasonResults = coroutineScope {
                (1..20).map { seasonNumber ->
                  async {
                      runCatching { resolver.resolve(completeItem, seasonNumber, null) }
                      .getOrDefault(emptyList())
                      .map { stream -> stream.copy(season = stream.season ?: seasonNumber) }
                  }
                }.awaitAll().flatten()
              }
              broadResults + knownSeasonResults + discoveredSeasonResults
            }.distinctBy(StreamOption::url)
            val allStreams = resolvedStreams.map { stream ->
              if (stream.season != null && stream.episode != null) stream else {
                val match = Regex("(?i)(?:^|[^a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?:[^a-z0-9]|$)").find(stream.title)
                stream.copy(season = stream.season ?: match?.groupValues?.getOrNull(1)?.toIntOrNull(), episode = stream.episode ?: match?.groupValues?.getOrNull(2)?.toIntOrNull())
              }
            }
            val resolverSeasons = allStreams.mapNotNull { stream ->
              val season = stream.season ?: return@mapNotNull null
              season to (stream.episode ?: 0)
            }.groupBy({ it.first }, { it.second }).map { (number, episodes) ->
              Season(number, episodes.filter { it > 0 }.distinct().sorted().map { episode ->
                Episode(episode, "Episode $episode", "", null)
              })
            }
            val itemWithResolverSeasons = completeItem.copy(seasons = (completeItem.seasons + resolverSeasons).distinctBy(Season::number).sortedBy(Season::number))
            Log.i(DIAG_TAG, "resolver item title=\"${completeItem.title}\" type=${completeItem.catalogType ?: completeItem.type} streams=${allStreams.size} playable=${allStreams.count { it.isPlayable }}")
            viewModel.initializeResolver(torrentInput("", intent, itemWithResolverSeasons), allStreams)
          }
        }
        TorrentSelectionScreen(
          state = state,
          onBack = ::closePicker,
          onRetry = viewModel::retry,
          onSelect = viewModel::select,
        )
      }
    }
  }

  private fun torrentInput(
    source: String,
    intent: Intent,
    item: MediaItem? = null,
    stream: StreamOption? = null,
    season: Int? = intent.getIntExtra("episode_season", -1).takeIf { it >= 0 },
    episode: Int? = intent.getIntExtra("episode_number", -1).takeIf { it >= 0 },
  ): TorrentSelectionInput {
    val title = item?.title ?: intent.getStringExtra(MediaUtils.EXTRA_MEDIA_TITLE)
    val description = item?.overview ?: intent.getStringExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION)
    return TorrentSelectionInput(
      source = source,
      title = title ?: intent.getStringExtra("title") ?: intent.getStringExtra("introdb_title"),
      description = description ?: intent.getStringExtra("description") ?: intent.getStringExtra("overview"),
      posterUrl = item?.posterUrl ?: intent.getStringExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL),
      backdropUrl = item?.backdropUrl ?: intent.getStringExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL),
      season = season,
      episode = episode,
      episodeTitle = intent.getStringExtra("episode_title"),
      episodeOverview = intent.getStringExtra("episode_overview"),
      episodeThumbnail = intent.getStringExtra("episode_thumbnail"),
      seasonsJson = item?.seasons?.let { Json.encodeToString(it) } ?: intent.getStringExtra("seasons_json"),
      fileIndex = stream?.torrentFileIndex ?: intent.getIntExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, -1).takeIf { it >= 0 },
    )
  }

  private fun resolverMediaItem(intent: Intent): MediaItem? {
    val title = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_TITLE)?.takeIf { it.isNotBlank() } ?: return null
    val seasons = runCatching {
      Json.decodeFromString<List<Season>>(intent.getStringExtra("seasons_json").orEmpty())
    }.getOrDefault(emptyList())
    return MediaItem(
      id = intent.getIntExtra("catalog_id", 0),
      type = intent.getStringExtra("catalog_type")?.let { runCatching { MediaType.valueOf(it) }.getOrNull() }
        ?: if (intent.getBooleanExtra("is_series", false)) MediaType.TV else MediaType.MOVIE,
      title = title,
      overview = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_DESCRIPTION).orEmpty(),
      posterUrl = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_POSTER_URL),
      backdropUrl = intent.getStringExtra(MediaUtils.EXTRA_MEDIA_BACKDROP_URL),
      imdbId = intent.getStringExtra("catalog_imdb_id"),
      providerId = intent.getStringExtra("catalog_provider_id"),
      catalogSourceId = intent.getStringExtra("catalog_source_id"),
      catalogType = intent.getStringExtra("catalog_type_name"),
      provider = intent.getStringExtra("catalog_provider")?.let { raw -> runCatching { CatalogProvider.valueOf(raw) }.getOrNull() } ?: CatalogProvider.CINEMETA,
      releaseYear = intent.getStringExtra("catalog_release_year"),
      contentRating = intent.getStringExtra("catalog_rating"),
      duration = intent.getStringExtra("catalog_duration"),
      genres = intent.getStringExtra("catalog_genres")?.split(" • ").orEmpty(),
      seasons = seasons,
    )
  }

  private fun openPlayer(request: TorrentSelectionLaunch) {
    if (playerLaunched || isFinishing) return
    playerLaunched = true
    val playbackIntent = Intent(intent).apply {
      action = Intent.ACTION_VIEW
      data = Uri.parse(request.source)
      setClass(this@TorrentSelectionActivity, PlayerActivity::class.java)
      addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("title", request.file.name)
      putExtra(MediaUtils.EXTRA_MEDIA_TITLE, request.file.name)
      putExtra(MediaUtils.EXTRA_TORRENT_SOURCE, request.source)
      putExtra(MediaUtils.EXTRA_TORRENT_FILE_INDEX, request.file.index)
      putExtra(MediaUtils.EXTRA_TORRENT_PREPARATION_ID, request.preparationId)
      putExtra("is_audio", request.file.mimeType.startsWith("audio/"))
    }
    startActivity(playbackIntent)
    finishWithoutAnimation()
  }

  private fun closePicker() {
    if (!playerLaunched) viewModel.cancel()
    finishWithoutAnimation()
  }

  @Suppress("DEPRECATION")
  private fun finishWithoutAnimation() {
    finish()
    overridePendingTransition(0, 0)
  }

  private fun extractTorrentSource(intent: Intent?): String? {
    intent ?: return null
    intent.getStringExtra(MediaUtils.EXTRA_TORRENT_SOURCE)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    intent.dataString?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    if (intent.action == Intent.ACTION_SEND) {
      val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
      }
      stream?.toString()?.takeIf(String::isNotBlank)?.let { return it }
      intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    }
    return null
  }
}

private fun String?.isDirectPlayableUrl(): Boolean {
  val value = this?.trim().orEmpty()
  if (!value.startsWith("http://") && !value.startsWith("https://")) return false
  val path = value.substringBefore('?').substringBefore('#').lowercase()
  return !path.endsWith(".torrent")
}
