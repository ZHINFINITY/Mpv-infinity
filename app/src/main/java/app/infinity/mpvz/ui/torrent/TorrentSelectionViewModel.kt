/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.torrent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.database.repository.NetworkStreamEntryRepository
import app.infinity.mpvz.domain.torrent.TorrentCatalog
import app.infinity.mpvz.domain.torrent.TorrentFileItem
import app.infinity.mpvz.domain.torrent.TorrentStreamingEngine
import app.infinity.mpvz.repository.wyzie.WyzieSearchRepository
import app.infinity.mpvz.repository.wyzie.WyzieTmdbResult
import app.infinity.mpvz.utils.media.MediaInfoParser
import app.infinity.mpvz.catalog.Episode
import app.infinity.mpvz.catalog.Season
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.net.URI

data class TorrentSelectionInput(
  val source: String,
  val headers: Map<String, String> = emptyMap(),
  val filename: String? = null,
  val isExternal: Boolean = false,
  val title: String? = null,
  val description: String? = null,
  val posterUrl: String? = null,
  val backdropUrl: String? = null,
  val season: Int? = null,
  val episode: Int? = null,
  val episodeTitle: String? = null,
  val episodeOverview: String? = null,
  val episodeThumbnail: String? = null,
  val qualityRank: Int = 0,
  val size: String? = null,
  val audioCodec: String? = null,
  val videoCodec: String? = null,
  val seasonsJson: String? = null,
  val fileIndex: Int? = null,
)

data class TorrentArtwork(
  val title: String,
  val description: String? = null,
  val posterUrl: String? = null,
  val backdropUrl: String? = null,
  val releaseYear: String? = null,
  val mediaType: String? = null,
  val season: Int? = null,
  val episode: Int? = null,
  val episodeTitle: String? = null,
  val episodeOverview: String? = null,
  val episodeThumbnail: String? = null,
  val seasons: List<app.infinity.mpvz.catalog.Season> = emptyList(),
)

data class EpisodeBrowserState(
  val seasons: List<Season>,
  val selectedSeason: Int? = null,
  val selectedEpisode: Episode? = null,
  val isResolving: Boolean = false,
  val error: String? = null,
)

sealed interface TorrentSelectionUiState {
  data object Loading : TorrentSelectionUiState

  data class Ready(
    val catalog: TorrentCatalog,
    val artwork: TorrentArtwork,
    val isLookingUpArtwork: Boolean,
    val launchingFileIndex: Int? = null,
    val resolverInputs: Map<Int, TorrentSelectionInput> = emptyMap(),
    val episodeBrowser: EpisodeBrowserState? = null,
    val showEpisodeList: Boolean = false,
  ) : TorrentSelectionUiState

  data class Error(
    val message: String,
  ) : TorrentSelectionUiState
}

data class TorrentSelectionLaunch(
  val source: String,
  val file: TorrentFileItem,
  val preparationId: String,
  val headers: Map<String, String> = emptyMap(),
  val isExternal: Boolean = false,
)

class TorrentSelectionViewModel(
  private val torrentStreamingEngine: TorrentStreamingEngine,
  private val streamEntryRepository: NetworkStreamEntryRepository,
  private val wyzieSearchRepository: WyzieSearchRepository,
) : ViewModel() {
  private val _uiState = MutableStateFlow<TorrentSelectionUiState>(TorrentSelectionUiState.Loading)
  val uiState: StateFlow<TorrentSelectionUiState> = _uiState.asStateFlow()

  private val launchChannel = Channel<TorrentSelectionLaunch>(Channel.BUFFERED)
  val launches = launchChannel.receiveAsFlow()

  private var input: TorrentSelectionInput? = null
  private var loadJob: Job? = null
  private var activePreparationId: String? = null
  private var handedToPlayer = false

  fun initialize(value: TorrentSelectionInput) {
    if (input != null) return
    open(value)
  }

  fun initializeResolver(value: TorrentSelectionInput, streams: List<app.infinity.mpvz.catalog.StreamOption>) {
    input = value
    if (streams.isEmpty()) {
      _uiState.value = TorrentSelectionUiState.Error("No compatible resolver provider returned links. Anime providers must advertise anime/series support and accept the title's ID prefix; IMDb-only providers cannot resolve Kitsu IDs.")
      return
    }
    val files = streams.mapIndexed { index, stream ->
      val episodePrefix = stream.season?.let { season -> stream.episode?.let { episode -> "S%02dE%02d ".format(season, episode) } }.orEmpty()
      val providerPrefix = stream.source?.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
      val displayName = stream.filename?.takeIf { it.isNotBlank() } ?: "$episodePrefix$providerPrefix${stream.title}"
      TorrentFileItem(index, displayName, displayName, parseResolverSize(stream.size), stream.mimeType ?: "video/x-matroska")
    }
    val resolverInputs = streams.mapIndexed { index, stream ->
      index to value.copy(
        source = stream.url,
        headers = stream.headers,
        filename = stream.filename,
        isExternal = stream.isExternal,
        qualityRank = stream.qualityRank,
        size = stream.size,
        audioCodec = stream.audioCodec,
        videoCodec = stream.videoCodec,
        season = stream.season ?: value.season,
        episode = stream.episode ?: value.episode,
        fileIndex = stream.torrentFileIndex,
      )
    }.toMap()
    _uiState.value = TorrentSelectionUiState.Ready(
      catalog = TorrentCatalog("resolver", "", "resolver", value.title ?: "Resolver results", files),
      artwork = TorrentArtwork(
        title = value.title ?: "Choose what to play",
        description = value.description,
        posterUrl = value.posterUrl,
        backdropUrl = value.backdropUrl,
        season = value.season,
        episode = value.episode,
        episodeTitle = value.episodeTitle,
        episodeOverview = value.episodeOverview,
        episodeThumbnail = value.episodeThumbnail,
        seasons = value.seasonsJson?.let { raw -> runCatching { kotlinx.serialization.json.Json.decodeFromString<List<app.infinity.mpvz.catalog.Season>>(raw) }.getOrDefault(emptyList()) } ?: emptyList(),
      ),
      isLookingUpArtwork = false,
      resolverInputs = resolverInputs,
    )
  }

  fun initializeResolverBrowser(value: TorrentSelectionInput, seasons: List<Season>) {
    input = value
    _uiState.value = TorrentSelectionUiState.Ready(
      catalog = TorrentCatalog("resolver", "", "resolver", value.title ?: "Choose an episode", emptyList()),
      artwork = TorrentArtwork(
        title = value.title ?: "Choose an episode",
        description = value.description,
        posterUrl = value.posterUrl,
        backdropUrl = value.backdropUrl,
        seasons = seasons,
      ),
      isLookingUpArtwork = false,
      episodeBrowser = EpisodeBrowserState(seasons = seasons, selectedSeason = seasons.firstOrNull()?.number),
      showEpisodeList = true,
    )
  }

  fun updateResolverBrowserSeasons(seasons: List<Season>) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    val browser = ready.episodeBrowser ?: return
    if (seasons.isEmpty()) return
    val selected = browser.selectedSeason?.takeIf { number -> seasons.any { it.number == number } } ?: seasons.first().number
    _uiState.value = ready.copy(
      artwork = ready.artwork.copy(seasons = seasons),
      episodeBrowser = browser.copy(seasons = seasons, selectedSeason = selected, error = null),
    )
  }

  fun setEpisodeResolving(season: Int, episode: Episode) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    val browser = ready.episodeBrowser ?: return
    _uiState.value = ready.copy(
      episodeBrowser = browser.copy(selectedSeason = season, selectedEpisode = episode, isResolving = true, error = null),
      showEpisodeList = true,
    )
  }

  fun setEpisodeError(message: String) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    val browser = ready.episodeBrowser ?: return
    _uiState.value = ready.copy(episodeBrowser = browser.copy(isResolving = false, error = message), showEpisodeList = true)
  }

  fun selectSeason(season: Int) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    val browser = ready.episodeBrowser ?: return
    _uiState.value = ready.copy(episodeBrowser = browser.copy(selectedSeason = season, selectedEpisode = null, error = null), showEpisodeList = true)
  }

  fun showEpisodeResults(value: TorrentSelectionInput, streams: List<app.infinity.mpvz.catalog.StreamOption>) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    val browser = ready.episodeBrowser ?: return
    input = value
    if (streams.isEmpty()) {
      setEpisodeError("No links were returned for this episode.")
      return
    }
    val files = streams.mapIndexed { index, stream ->
      val providerPrefix = stream.source?.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
      val displayName = stream.filename?.takeIf { it.isNotBlank() } ?: "$providerPrefix${stream.title}"
      TorrentFileItem(index, displayName, displayName, parseResolverSize(stream.size), stream.mimeType ?: "video/x-matroska")
    }
    val resolverInputs = streams.mapIndexed { index, stream ->
      index to value.copy(source = stream.url, headers = stream.headers, filename = stream.filename, isExternal = stream.isExternal, qualityRank = stream.qualityRank, size = stream.size, audioCodec = stream.audioCodec, videoCodec = stream.videoCodec, fileIndex = stream.torrentFileIndex)
    }.toMap()
    _uiState.value = ready.copy(
      catalog = TorrentCatalog("resolver", "", "resolver", value.title ?: "Episode links", files),
      artwork = ready.artwork.copy(season = value.season, episode = value.episode, episodeTitle = value.episodeTitle, episodeOverview = value.episodeOverview, episodeThumbnail = value.episodeThumbnail),
      resolverInputs = resolverInputs,
      episodeBrowser = browser.copy(isResolving = false, error = null),
      showEpisodeList = false,
    )
  }

  fun showEpisodeList() {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    val browser = ready.episodeBrowser ?: return
    _uiState.value = ready.copy(showEpisodeList = true, episodeBrowser = browser.copy(isResolving = false, error = null), catalog = ready.catalog.copy(playableFiles = emptyList()), resolverInputs = emptyMap())
  }

  /** Opens a new torrent in the same picker host, replacing any previous picker session. */
  fun open(value: TorrentSelectionInput) {
    input = value
    load(value)
  }

  fun retry() {
    val currentInput = input ?: return
    load(currentInput)
  }

  fun select(fileIndex: Int) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    if (ready.launchingFileIndex != null) return
    val file = ready.catalog.playableFiles.firstOrNull { it.index == fileIndex } ?: return
    ready.resolverInputs[fileIndex]?.let { resolverInput ->
      if (resolverInput.isExternal || resolverInput.source.startsWith("http://") || resolverInput.source.startsWith("https://")) launchDirect(resolverInput, file)
      else open(resolverInput)
    } ?: launch(ready.catalog, file)
  }

  private fun launchDirect(input: TorrentSelectionInput, file: TorrentFileItem) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    if (ready.launchingFileIndex != null) return
    _uiState.value = ready.copy(launchingFileIndex = file.index, isLookingUpArtwork = false)
    handedToPlayer = true
    activePreparationId = null
    launchChannel.trySend(TorrentSelectionLaunch(source = input.source, file = file, preparationId = "", headers = input.headers, isExternal = input.isExternal))
  }

  fun cancel() {
    loadJob?.cancel()
    loadJob = null
    if (!handedToPlayer) {
      activePreparationId?.let(torrentStreamingEngine::discardPreparation)
    }
    activePreparationId = null
  }

  fun onPlayerReturned() {
    handedToPlayer = false
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    if (ready.launchingFileIndex != null) {
      _uiState.value = ready.copy(launchingFileIndex = null)
    }
  }

  private fun load(value: TorrentSelectionInput) {
    loadJob?.cancel()
    if (!handedToPlayer) activePreparationId?.let(torrentStreamingEngine::discardPreparation)
    activePreparationId = null
    handedToPlayer = false
    _uiState.value = TorrentSelectionUiState.Loading

    loadJob =
      viewModelScope.launch {
        try {
          val catalog = torrentStreamingEngine.prepareTorrent(value.source)
          activePreparationId = catalog.preparationId

          persistCatalog(catalog)

          val initialArtwork =
            TorrentArtwork(
              title = value.title.safeText(MAX_TITLE_LENGTH) ?: prettyTorrentTitle(catalog.torrentName),
              description = (value.episodeOverview ?: value.description).safeText(MAX_DESCRIPTION_LENGTH),
              posterUrl = safeRemoteImageUrl(value.posterUrl),
              backdropUrl = safeRemoteImageUrl(value.backdropUrl),
              season = value.season,
              episode = value.episode,
              episodeTitle = value.episodeTitle,
              episodeOverview = value.episodeOverview,
              episodeThumbnail = safeRemoteImageUrl(value.episodeThumbnail),
              seasons = value.seasonsJson?.let { raw -> runCatching { kotlinx.serialization.json.Json.decodeFromString<List<app.infinity.mpvz.catalog.Season>>(raw) }.getOrDefault(emptyList()) } ?: emptyList(),
            )
          val needsArtworkLookup =
            initialArtwork.description == null ||
              initialArtwork.posterUrl == null ||
              initialArtwork.backdropUrl == null

          _uiState.value =
            TorrentSelectionUiState.Ready(
              catalog = catalog,
              artwork = initialArtwork,
              isLookingUpArtwork = needsArtworkLookup && catalog.playableFiles.size > 1,
            )

          val requestedFile =
            value.fileIndex?.let { index -> catalog.playableFiles.firstOrNull { it.index == index } }
              ?: if (value.season != null && value.episode != null) {
                catalog.playableFiles.firstOrNull { file ->
                val byName = MediaInfoParser.parse(file.name)
                val byPath = MediaInfoParser.parse(file.path)
                (byName.season == value.season && byName.episode == value.episode) ||
                  (byPath.season == value.season && byPath.episode == value.episode)
                }
              } else null
          when {
            requestedFile != null -> launch(catalog, requestedFile)
            catalog.playableFiles.size == 1 -> launch(catalog, catalog.playableFiles.single())
            needsArtworkLookup -> launchArtworkLookup(catalog, initialArtwork)
          }
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Throwable) {
          activePreparationId = null
          _uiState.value =
            TorrentSelectionUiState.Error(
              error.message.safeText(MAX_ERROR_LENGTH) ?: "Couldn't open this torrent.",
            )
        }
      }
  }

  private suspend fun persistCatalog(catalog: TorrentCatalog) {
    try {
      streamEntryRepository.replaceTorrentFiles(
        canonicalSourceUri = catalog.source,
        infoHash = catalog.infoHash,
        files =
          catalog.playableFiles.map { file ->
            NetworkStreamEntryRepository.TorrentFile(
              index = file.index,
              path = file.path,
              name = file.name,
              size = file.size,
            )
          },
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      // Catalog persistence must never prevent immediate playback.
    }
  }

  private fun launchArtworkLookup(
    catalog: TorrentCatalog,
    currentArtwork: TorrentArtwork,
  ) {
    viewModelScope.launch {
      val rawTitle = currentArtwork.title.ifBlank { catalog.torrentName }
      val parsed = MediaInfoParser.parse(rawTitle)
      val queryCandidates = mutableListOf<String>()

      if (parsed.title.isNotBlank()) queryCandidates.add(parsed.title)

      val cleaned = cleanSearchTitle(rawTitle)
      if (cleaned.isNotBlank() && !queryCandidates.contains(cleaned)) queryCandidates.add(cleaned)

      val beforeDash = rawTitle.substringBefore('-').trim()
      val cleanedBeforeDash = cleanSearchTitle(beforeDash)
      if (cleanedBeforeDash.length >= MIN_SEARCH_LENGTH && !queryCandidates.contains(cleanedBeforeDash)) {
        queryCandidates.add(cleanedBeforeDash)
      }

      val beforeColon = rawTitle.substringBefore(':').trim()
      val cleanedBeforeColon = cleanSearchTitle(beforeColon)
      if (cleanedBeforeColon.length >= MIN_SEARCH_LENGTH && !queryCandidates.contains(cleanedBeforeColon)) {
        queryCandidates.add(cleanedBeforeColon)
      }

      var match: WyzieTmdbResult? = null
      for (query in queryCandidates) {
        if (query.length < MIN_SEARCH_LENGTH) continue
        val result = wyzieSearchRepository.findBestMediaMatch(query, parsed.year).getOrNull()
        if (result != null) {
          match = result
          break
        }
      }

      val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return@launch
      if (ready.catalog.preparationId != catalog.preparationId || ready.launchingFileIndex != null) return@launch
      val updatedArtwork =
        currentArtwork.copy(
          title = currentArtwork.title.ifBlank { match?.title.orEmpty() }.ifBlank { catalog.torrentName },
          description = currentArtwork.description ?: match?.overview.safeText(MAX_DESCRIPTION_LENGTH),
          posterUrl = currentArtwork.posterUrl ?: tmdbImageUrl(match?.poster, "w500"),
          backdropUrl = currentArtwork.backdropUrl ?: tmdbImageUrl(match?.backdrop, "w1280"),
          releaseYear = match?.releaseYear,
          mediaType = match?.mediaType,
        )
      _uiState.value =
        ready.copy(
          artwork = updatedArtwork,
          isLookingUpArtwork = false,
        )
      runCatching {
        streamEntryRepository.updateTorrentArtwork(
          infoHash = catalog.infoHash,
          title = updatedArtwork.title,
          posterUrl = updatedArtwork.posterUrl,
          backdropUrl = updatedArtwork.backdropUrl,
          overview = updatedArtwork.description,
          releaseYear = updatedArtwork.releaseYear,
          mediaType = updatedArtwork.mediaType,
        )
      }
    }
  }

  private fun launch(
    catalog: TorrentCatalog,
    file: TorrentFileItem,
  ) {
    val ready = _uiState.value as? TorrentSelectionUiState.Ready ?: return
    if (ready.catalog.preparationId != catalog.preparationId || ready.launchingFileIndex != null) return
    _uiState.value = ready.copy(launchingFileIndex = file.index, isLookingUpArtwork = false)
    handedToPlayer = true
    activePreparationId = null
    launchChannel.trySend(
      TorrentSelectionLaunch(
        source = catalog.source,
        file = file,
        preparationId = catalog.preparationId,
      ),
    )
  }

  override fun onCleared() {
    cancel()
    launchChannel.close()
    super.onCleared()
  }

  companion object {
    private const val MAX_TITLE_LENGTH = 200
    private const val MAX_DESCRIPTION_LENGTH = 2_000
    private const val MAX_ERROR_LENGTH = 240
    private const val MIN_SEARCH_LENGTH = 3

    fun factory(
      torrentStreamingEngine: TorrentStreamingEngine,
      streamEntryRepository: NetworkStreamEntryRepository,
      wyzieSearchRepository: WyzieSearchRepository,
    ): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
          TorrentSelectionViewModel(torrentStreamingEngine, streamEntryRepository, wyzieSearchRepository) as T
      }
  }
}

private val seasonEpisodeRegex = Regex("(?i)\\bS\\d{1,2}[\\s.:_-]*E\\d{1,4}\\b")
private val crossFormatRegex = Regex("(?i)\\b\\d{1,2}x\\d{1,4}\\b")
private val episodeWordRegex = Regex("(?i)\\bep(?:isode)?[\\s.:_-]*\\d{1,4}\\b")
private val seasonRegex = Regex("(?i)\\bS(?:eason)?[\\s.:_-]*\\d{1,2}\\b")
private val knownExtensionRegex = Regex("(?i)\\.(?:torrent|mkv|mp4|m4v|webm|avi|mov|ts|m2ts|mp3|m4a|flac|ogg)$")
private val releaseNoiseRegex =
  Regex(
    "(?i)\\b(?:2160p|1080p|720p|480p|uhd|hdr10?|dv|dolby[ ._-]*vision|bluray|brrip|" +
      "web[ ._-]*dl|webrip|hdtv|x26[45]|hevc|av1|aac|dts|atmos|proper|repack)\\b.*$",
  )

private fun prettyTorrentTitle(value: String): String =
  value
    .substringAfterLast('/')
    .replace(knownExtensionRegex, "")
    .replace(seasonEpisodeRegex, " ")
    .replace(crossFormatRegex, " ")
    .replace(episodeWordRegex, " ")
    .replace(seasonRegex, " ")
    .replace(releaseNoiseRegex, " ")
    .replace(Regex("[\\[\\]【】()（）]"), " ")
    .replace(Regex("[._]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim(' ', '-', '_', ':', '.')
    .ifBlank { "Torrent" }

private fun cleanSearchTitle(value: String): String =
  prettyTorrentTitle(value)
    .replace(Regex("\\s+"), " ")
    .trim()

private fun tmdbImageUrl(
  path: String?,
  size: String,
): String? {
  val value = path?.trim()?.takeIf(String::isNotBlank) ?: return null
  return when {
    safeRemoteImageUrl(value) != null -> safeRemoteImageUrl(value)
    value.startsWith('/') -> "https://image.tmdb.org/t/p/$size$value"
    else -> "https://image.tmdb.org/t/p/$size/$value"
  }
}

private fun safeRemoteImageUrl(value: String?): String? {
  val candidate = value?.trim()?.takeIf(String::isNotBlank) ?: return null
  return runCatching {
    val uri = URI(candidate)
    candidate.takeIf {
      uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        !uri.host.equals("localhost", ignoreCase = true) &&
        uri.host != "127.0.0.1" &&
        uri.host != "::1"
    }
  }.getOrNull()
}

private fun String?.safeText(maxLength: Int): String? =
  this
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.take(maxLength)

private fun parseResolverSize(raw: String?): Long {
  val value = raw?.trim()?.uppercase() ?: return 0L
  val number = Regex("[0-9]+(?:\\.[0-9]+)?").find(value)?.value?.toDoubleOrNull() ?: return 0L
  return when {
    "TB" in value -> (number * 1024 * 1024 * 1024 * 1024).toLong()
    "GB" in value -> (number * 1024 * 1024 * 1024).toLong()
    "MB" in value -> (number * 1024 * 1024).toLong()
    "KB" in value -> (number * 1024).toLong()
    else -> number.toLong()
  }
}
