package app.infinity.mpvz.catalog

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.catalog.nuvio.PluginRepository
import app.infinity.mpvz.preferences.AdvancedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.java.KoinJavaComponent.inject

/** Nuvio home/catalog/search and direct-HTTPS stream workflow. Torrent selection is intentionally absent. */
class CatalogViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "MpvCatalogDiag"
    private const val SEARCH_DEBOUNCE_MS = 250L

    fun Factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
          CatalogViewModel(application) as T
      }
  }

  private val settings = CatalogSettings(application)
  private val _catalogSources = MutableStateFlow(settings.catalogSources())
  val catalogSources: StateFlow<List<CatalogSource>> = _catalogSources.asStateFlow()
  private val catalogRepository = StremioCatalogRepository()
  private val streamRepository = StremioStreamRepository()
  private val metadataRepository = StremioMetadataRepository()
  private val nuvioPlugins = PluginRepository(application)
  private val nuvioMetadata = app.infinity.mpvz.catalog.nuvio.TmdbMetadataRepository()
  private val tvdbMetadata = app.infinity.mpvz.catalog.nuvio.TvdbMetadataRepository()
  private val checkedNuvioMigrationUrls = mutableSetOf<String>()
  private val _state = MutableStateFlow(CatalogState())
  val state: StateFlow<CatalogState> = _state.asStateFlow()
  private val _playbackStream = MutableStateFlow<StreamOption?>(null)
  val playbackStream: StateFlow<StreamOption?> = _playbackStream.asStateFlow()
  private val historyPrefs = application.getSharedPreferences(StreamWatchHistory.PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
  private val historyProgressPrefs = application.getSharedPreferences(StreamWatchHistory.PROGRESS_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
  private val advancedPreferences: AdvancedPreferences by inject(AdvancedPreferences::class.java)
  private val historyPrefsChangeListener =
    android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == null || key == StreamWatchHistory.ORDER_KEY || key == StreamWatchHistory.KEYS_KEY) {
        refreshHistory()
      }
    }

  private var searchJob: Job? = null
  private var homeLoadJob: Job? = null
  private var metadataJob: Job? = null
  private var streamResolveJob: Job? = null
  private var cachedHomeItems: List<MediaItem> = emptyList()
  private var posterEnrichmentQuery: String? = null
  private val posterEnrichmentKeys = mutableSetOf<String>()
  private val posterFailureRequests = mutableSetOf<String>()
  private val posterEnrichmentJobs = mutableSetOf<Job>()
  private val posterArtworkSemaphore = Semaphore(3)

  init {
    historyPrefs.registerOnSharedPreferenceChangeListener(historyPrefsChangeListener)
    viewModelScope.launch {
      advancedPreferences.enableRecentlyPlayed.changes().distinctUntilChanged().collect { enabled ->
        _state.update { current -> current.copy(recentItems = reconcileHistory(current.items, enabled)) }
      }
    }
    if (historyPrefs.getInt("schema_version", 0) < 3) {
      historyPrefs.edit().remove("keys").remove("order").putInt("schema_version", 3).apply()
    }
    loadTrending()
    viewModelScope.launch { migrateNuvioScraperRepositories() }
  }

  override fun onCleared() {
    historyPrefs.unregisterOnSharedPreferenceChangeListener(historyPrefsChangeListener)
    super.onCleared()
  }

  fun setQuery(query: String) {
    searchJob?.cancel()
    homeLoadJob?.cancel()
    resetPosterEnrichment(query.trim())
    _state.update { it.copy(query = query, items = emptyList(), isLoading = true, isLoadingMore = false, error = null, catalogPage = 1, canLoadMore = true) }
    searchJob = viewModelScope.launch {
      delay(SEARCH_DEBOUNCE_MS)
      if (_state.value.query != query) return@launch
      if (query.isBlank()) {
        val cached = cachedHomeItems
        if (cached.isNotEmpty()) {
          _state.update { it.copy(items = cached, isLoading = false, catalogPage = 1, canLoadMore = true) }
          queueMissingCatalogPosters(cached, "")
        } else {
          loadTrending()
        }
      } else {
        runSearch(query.trim())
      }
    }
  }

  fun loadMore(pageCount: Int = 1) {
    val snapshot = _state.value
    if (snapshot.isLoadingMore || snapshot.isLoading || !snapshot.canLoadMore || snapshot.items.isEmpty()) return
    viewModelScope.launch {
      _state.update { it.copy(isLoadingMore = true, error = null) }
      val pages = (snapshot.catalogPage + 1..snapshot.catalogPage + pageCount.coerceIn(1, 3)).toList()
      val results = coroutineScope {
        pages.map { page ->
          async { page to runCatching { loadFromAddons(snapshot.query.takeIf(String::isNotBlank), page) } }
        }.awaitAll()
      }
      val more = results.flatMap { it.second.getOrDefault(emptyList()) }
      val lastSuccessfulPage = results.lastOrNull { it.second.isSuccess }?.first ?: snapshot.catalogPage
      val failure = results.firstOrNull { it.second.isFailure }?.second?.exceptionOrNull()
      _state.update { current ->
        if (current.query != snapshot.query) current.copy(isLoadingMore = false)
        else {
          val merged = (current.items + more).distinctBy(::catalogItemIdentityKey)
          current.copy(
            items = merged,
            catalogPage = lastSuccessfulPage,
            canLoadMore = results.all { it.second.isSuccess } && merged.size > current.items.size && more.isNotEmpty(),
            isLoadingMore = false,
            error = failure?.let { redactAddonConfigurationFromLog(it.message.orEmpty()) },
          )
        }
      }
      if (_state.value.query.trim() == snapshot.query.trim()) {
        queueMissingCatalogPosters(_state.value.items, snapshot.query.trim())
      }
    }
  }

  fun onCatalogPosterLoadFailure(item: MediaItem) {
    val query = _state.value.query.trim()
    if (posterEnrichmentQuery != query) resetPosterEnrichment(query)
    val key = catalogItemIdentityKey(item)
    if (!posterFailureRequests.add(key)) return
    queueMissingCatalogPosters(listOf(item), query, forcePosterKeys = setOf(key))
  }

  fun reloadAddonConfiguration() {
    val sources = settings.catalogSources()
    val changed = sources != _catalogSources.value
    _catalogSources.value = sources
    if (changed) {
      if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query.trim()) }
    }
    viewModelScope.launch { migrateNuvioScraperRepositories() }
  }

  suspend fun addCatalogSource(rawUrl: String): Result<CatalogSource> {
    return runCatching {
      val manifestUrl = normalizeCatalogManifestUrl(rawUrl)
      require(_catalogSources.value.none { it.manifestUrl.equals(manifestUrl, ignoreCase = true) }) {
        "This catalog add-on is already installed."
      }
      val name = catalogRepository.validateCatalogManifest(manifestUrl)
      val source = if (manifestUrl.equals(BUILTIN_CATALOG_MANIFEST_URL, ignoreCase = true)) {
        DEFAULT_BUILTIN_CATALOG_SOURCE
      } else {
        CatalogSource(
          id = "catalog-${manifestUrl.hashCode().toUInt().toString(16)}",
          name = name,
          manifestUrl = manifestUrl,
        )
      }
      val updated = (_catalogSources.value + source).distinctBy { it.manifestUrl.lowercase() }
      settings.saveCatalogSources(updated)
      _catalogSources.value = updated
      loadTrending()
      source
    }
  }

  fun setCatalogSourceEnabled(sourceId: String, enabled: Boolean) {
    updateCatalogSources { sources -> sources.map { if (it.id == sourceId) it.copy(isEnabled = enabled) else it } }
  }

  fun removeCatalogSource(sourceId: String) {
    updateCatalogSources { sources -> sources.filterNot { it.id == sourceId } }
  }

  fun currentCatalogSources(): List<CatalogSource> = settings.catalogSources()

  fun refreshSelectedDetails() {
    _state.value.selectedItem?.let(::showDetails)
  }

  fun refresh() {
    viewModelScope.launch { refreshAll() }
  }

  fun showDetails(item: MediaItem) {
    val itemKey = catalogItemIdentityKey(item)
    metadataJob?.cancel()
    streamResolveJob?.cancel()
    _state.update {
      it.copy(
        metadataLoadingKey = itemKey,
        resolvingKey = null,
        selectedItem = item,
        streamOptions = emptyList(),
        streamTitle = null,
        selectedSeason = item.seasons.firstOrNull()?.number,
        selectedEpisode = null,
        selectedEpisodeVideoId = null,
        error = null,
      )
    }
    metadataJob = viewModelScope.launch {
      val sources = settings.catalogSources()
      val addonMetadata = runCatching { metadataRepository.loadMetadata(item, sources) }.getOrNull() ?: item
      val metadata = runCatching { nuvioMetadata.load(addonMetadata, nuvioPlugins.tmdbApiKey()) }.getOrNull() ?: addonMetadata
      val tvdbArtwork = if (metadata.type == MediaType.TV) {
        runCatching { tvdbMetadata.load(item, nuvioPlugins.tvdbApiKey()) }
          .onFailure { Log.e(TAG, "TVDB primary artwork failed title=${item.title}", it) }
          .getOrNull()
      } else null
      val artworkMetadata = tvdbArtwork?.takeIf { it.seasons.any { season -> !season.posterUrl.isNullOrBlank() } }?.let {
        metadata.copy(seasons = mergeAuthoritativeSeasonArtwork(metadata.seasons, it.seasons))
      } ?: metadata
      val sanitizedArtworkMetadata = artworkMetadata.copy(seasons = sanitizeSeasons(artworkMetadata.seasons))
      Log.i(TAG, "details artwork title=${item.title} seasons=${sanitizedArtworkMetadata.seasons.size} posters=${sanitizedArtworkMetadata.seasons.count { !it.posterUrl.isNullOrBlank() }} urls=${sanitizedArtworkMetadata.seasons.joinToString { "S${it.number}:${it.posterUrl?.substringAfterLast('/') ?: "none"}" }}")
      val completedItem = if (sanitizedArtworkMetadata.type == MediaType.TV && sanitizedArtworkMetadata.seasons.isEmpty()) {
        val seasons = runCatching { metadataRepository.loadSeasons(sanitizedArtworkMetadata, sources) }.getOrDefault(emptyList())
        if (seasons.isEmpty()) sanitizedArtworkMetadata else sanitizedArtworkMetadata.copy(seasons = sanitizeSeasons(seasons))
      } else sanitizedArtworkMetadata
      _state.update { current ->
        if (current.selectedItem?.let(::catalogItemIdentityKey) == itemKey) current.copy(
          selectedItem = completedItem,
          selectedSeason = current.selectedSeason ?: completedItem.seasons.firstOrNull()?.number,
          metadataLoadingKey = null,
        ) else current
      }
    }
  }

  private fun mergeAuthoritativeSeasonArtwork(existing: List<Season>, artwork: List<Season>): List<Season> {
    val existingByNumber = existing.associateBy { it.number }
    val artworkByNumber = artwork.associateBy { it.number }
    // TVDB is authoritative for positive season existence. Keep the catalog's
    // Season 0 specials, but drop positive future/unreleased seasons absent
    // from TVDB instead of allowing their old poster to be reused.
    val numbers = (artworkByNumber.keys + existingByNumber.keys.filter { it == 0 }).sorted()
    return numbers.mapNotNull { number ->
      val original = existingByNumber[number]
      val fresh = artworkByNumber[number]
      when {
        original == null -> fresh
        // Keep the catalog's episodes, but never keep an old thumbnail/poster
        // for a season that TVDB did not return.
        fresh == null -> original.copy(posterUrl = null)
        else -> original.copy(posterUrl = fresh.posterUrl)
      }
    }
  }

  /** Built-in and third-party metadata occasionally encode an air year as season. */
  private fun sanitizeSeasons(seasons: List<Season>): List<Season> = seasons
    .filter { it.number in 0..100 }
    .groupBy { it.number }
    .map { (_, candidates) -> candidates.firstOrNull { !it.posterUrl.isNullOrBlank() } ?: candidates.first() }
    .sortedBy { it.number }

  fun resolve(item: MediaItem, season: Int? = null, episode: Int? = null, episodeVideoId: String? = null) {
    val itemKey = catalogItemIdentityKey(item)
    streamResolveJob?.cancel()
    streamResolveJob = viewModelScope.launch {
      _state.update {
        it.copy(
          resolvingKey = itemKey,
          streamTitle = item.title,
          error = null,
          selectedSeason = season,
          selectedEpisode = episode,
          selectedEpisodeVideoId = episodeVideoId,
          streamOptions = emptyList(),
        )
      }

      // Start both provider families together. Nuvio providers already publish
      // batches progressively; parallel startup removes the old sequential wait.
      coroutineScope {
        val pluginJob = async {
          runCatching {
            nuvioPlugins.resolve(item, season, episode) { batch ->
              _state.update { current ->
                if (current.resolvingKey == itemKey && current.selectedSeason == season &&
                  current.selectedEpisode == episode && current.selectedEpisodeVideoId == episodeVideoId
                ) current.copy(streamOptions = mergeStreamOptions(current.streamOptions, batch)) else current
              }
            }
          }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Nuvio stream resolution failed")
          }.getOrDefault(emptyList())
        }
        val addonJob = async {
          runCatching {
            streamRepository.resolve(
              item = item,
              season = season,
              episode = episode,
              sources = settings.catalogSources(),
              episodeVideoId = episodeVideoId,
              resolveImdbId = { candidate -> nuvioMetadata.resolveImdbId(candidate, nuvioPlugins.tmdbApiKey()) },
              onBatch = { batch ->
                _state.update { current ->
                  if (current.resolvingKey == itemKey && current.selectedSeason == season &&
                    current.selectedEpisode == episode && current.selectedEpisodeVideoId == episodeVideoId
                  ) current.copy(streamOptions = mergeStreamOptions(current.streamOptions, batch)) else current
                }
              },
            )
          }
            .onFailure { error ->
              if (error is CancellationException) throw error
              Log.w(TAG, "Stremio stream resolution failed")
            }.getOrDefault(emptyList())
        }
        val (pluginStreams, addonStreams) = awaitAll(pluginJob, addonJob)
          .map { it as List<StreamOption> }
        _state.update { current ->
          val all = mergeStreamOptions(current.streamOptions, pluginStreams + addonStreams)
          current.copy(
            streamOptions = all,
            error = if (all.isEmpty()) "The enabled providers returned no stream links for this title or episode." else null,
          )
        }
      }
      Log.i(TAG, "Stream resolution complete links=${_state.value.streamOptions.size}")
      _state.update { current ->
        if (current.selectedItem?.let(::catalogItemIdentityKey) == itemKey && current.resolvingKey == itemKey &&
          current.selectedSeason == season && current.selectedEpisode == episode && current.selectedEpisodeVideoId == episodeVideoId
        ) {
          current.copy(resolvingKey = null)
        } else current
      }
    }
  }

  fun playStream(stream: StreamOption) {
    if (!(stream.url.startsWith("http://", ignoreCase = true) || stream.url.startsWith("https://", ignoreCase = true))) return
    if (_state.value.selectedItem == null) return
    _playbackStream.value = stream
  }

  /** Re-read the rail after PlayerActivity has committed real playback state. */
  fun refreshHistory() {
    _state.update { it.copy(recentItems = reconcileHistory(it.items)) }
  }

  fun consumePlaybackStream() { _playbackStream.value = null }

  fun selectSeason(season: Int) {
    streamResolveJob?.cancel()
    _state.update { it.copy(selectedSeason = season, streamOptions = emptyList(), streamTitle = null, resolvingKey = null, selectedEpisode = null, selectedEpisodeVideoId = null, error = null) }
  }

  fun closeStreams() {
    streamResolveJob?.cancel()
    streamResolveJob = null
    _state.update { it.copy(streamOptions = emptyList(), streamTitle = null, resolvingKey = null, selectedEpisode = null, selectedEpisodeVideoId = null, error = null) }
  }
  fun closeDetails() {
    metadataJob?.cancel()
    streamResolveJob?.cancel()
    metadataJob = null
    streamResolveJob = null
    _state.update { it.copy(streamOptions = emptyList(), streamTitle = null, metadataLoadingKey = null, resolvingKey = null, selectedItem = null, selectedSeason = null, selectedEpisode = null, selectedEpisodeVideoId = null, error = null) }
  }

  fun retry() {
    if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query.trim()) }
  }

  suspend fun refreshAll() {
    searchJob?.cancel()
    homeLoadJob?.cancel()
    resetPosterEnrichment(_state.value.query.trim())
    if (_state.value.query.isBlank()) {
      _state.update { it.copy(isLoading = true, isLoadingMore = false, error = null) }
      runCatching { loadFromAddons(null) }
        .onSuccess { items ->
          cachedHomeItems = items
          _state.update { it.copy(items = items, recentItems = reconcileHistory(items), isLoading = false, isLoadingMore = false, catalogPage = 1, canLoadMore = items.isNotEmpty(), error = null) }
          if (_state.value.query.isBlank()) queueMissingCatalogPosters(items, "")
        }
        .onFailure { error ->
          if (error is CancellationException) throw error
          _state.update { it.copy(isLoading = false, isLoadingMore = false, error = redactAddonConfigurationFromLog(error.message.orEmpty())) }
        }
    } else {
      runSearch(_state.value.query.trim())
    }
  }

  private fun updateCatalogSources(transform: (List<CatalogSource>) -> List<CatalogSource>) {
    val updated = transform(_catalogSources.value)
    if (updated == _catalogSources.value) return
    settings.saveCatalogSources(updated)
    _catalogSources.value = updated
    if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query.trim()) }
  }

  private fun loadTrending() {
    homeLoadJob?.cancel()
    resetPosterEnrichment("")
    homeLoadJob = viewModelScope.launch {
      val sources = requestableCatalogSources(settings.catalogSources())
      if (sources.isEmpty()) {
        cachedHomeItems = emptyList()
        _state.update {
          it.copy(
            items = emptyList(),
            recentItems = emptyList(),
            isLoading = false,
            isLoadingMore = false,
            catalogPage = 1,
            canLoadMore = false,
            error = BUILT_IN_CATALOG_UNAVAILABLE_MESSAGE,
          )
        }
        return@launch
      }
      _state.update { it.copy(isLoading = true, isLoadingMore = false, error = null) }
      runCatching { loadFromAddons(null) }
        .onSuccess { items ->
          cachedHomeItems = items
          if (_state.value.query.isBlank()) {
            _state.update { it.copy(items = items, recentItems = reconcileHistory(items), isLoading = false, catalogPage = 1, canLoadMore = items.isNotEmpty()) }
            queueMissingCatalogPosters(items, "")
          }
        }
        .onFailure { error ->
          if (error is CancellationException) throw error
          _state.update { it.copy(isLoading = false, error = redactAddonConfigurationFromLog(error.message.orEmpty())) }
        }
    }
  }

  private suspend fun runSearch(query: String) {
    if (query.isBlank()) return
    resetPosterEnrichment(query.trim())
    _state.update { it.copy(isLoading = true, isLoadingMore = false, error = null, items = emptyList(), catalogPage = 1, canLoadMore = true) }
    val sources = requestableCatalogSources(settings.catalogSources())
    if (sources.isEmpty()) {
      _state.update {
        it.copy(isLoading = false, canLoadMore = false, error = BUILT_IN_CATALOG_UNAVAILABLE_MESSAGE)
      }
      return
    }
    val result = runCatching { loadFromAddons(query) }
    if (_state.value.query.trim() != query) return
    result.fold(
      onSuccess = { items ->
        _state.update { it.copy(items = items, recentItems = reconcileHistory(items), isLoading = false, catalogPage = 1, canLoadMore = items.isNotEmpty(), error = null) }
        queueMissingCatalogPosters(items, query.trim())
      },
      onFailure = { error ->
        if (error is CancellationException) throw error
        _state.update { it.copy(items = emptyList(), isLoading = false, canLoadMore = false, error = redactAddonConfigurationFromLog(error.message.orEmpty())) }
      },
    )
  }

  private suspend fun loadFromAddons(query: String?, page: Int = 1): List<MediaItem> {
    val sources = requestableCatalogSources(settings.catalogSources())
    if (sources.isEmpty()) throw IllegalStateException(BUILT_IN_CATALOG_UNAVAILABLE_MESSAGE)
    Log.i(TAG, "load queryLength=${query?.length ?: 0} catalogAddons=${sources.size} page=$page")
    val results = loadCatalogSourcesIndependently(sources) { source ->
      withTimeoutOrNull(45_000L) { catalogRepository.load(source, query, page) }
    }
    val failures = results.mapNotNull { (source, result) ->
      val message = when {
        result.isFailure -> "${source.name}: ${redactAddonConfigurationFromLog(result.exceptionOrNull()?.message.orEmpty())}"
        result.getOrNull() == null -> "${source.name}: catalog request timed out"
        else -> null
      }
      if (message != null) Log.w(TAG, "catalog source failed id=${source.id}: ${redactAddonConfigurationFromLog(message)}")
      message
    }
    val items = results.flatMap { (_, result) -> result.getOrNull().orEmpty() }
      .distinctBy(::catalogItemIdentityKey)
    if (items.isEmpty() && failures.isNotEmpty()) {
      throw IllegalStateException(failures.distinct().joinToString("\n"))
    }
    val distinctItems = items
    Log.i(TAG, "load complete queryLength=${query?.length ?: 0} items=${distinctItems.size}")
    return distinctItems
  }

  private fun resetPosterEnrichment(query: String) {
    posterEnrichmentJobs.toList().forEach { it.cancel() }
    posterEnrichmentJobs.clear()
    posterEnrichmentKeys.clear()
    posterFailureRequests.clear()
    posterEnrichmentQuery = query
  }

  private fun queueMissingCatalogPosters(
    items: List<MediaItem>,
    query: String,
    forcePosterKeys: Set<String> = emptySet(),
  ) {
    val normalizedQuery = query.trim()
    if (posterEnrichmentQuery != normalizedQuery) resetPosterEnrichment(normalizedQuery)
    val candidates = items.asSequence()
      .filter { it.posterUrl.isNullOrBlank() || catalogItemIdentityKey(it) in forcePosterKeys }
      .distinctBy(::catalogItemIdentityKey)
      .filter { item ->
        val key = catalogItemIdentityKey(item)
        posterEnrichmentKeys.add(key) || key in forcePosterKeys
      }
      .toList()
    if (candidates.isEmpty()) return
    val candidateKeys = candidates.map(::catalogItemIdentityKey).toSet()

    lateinit var enrichmentJob: Job
    enrichmentJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
      try {
        val tmdbApiKey = nuvioPlugins.tmdbApiKey()
        val tvdbApiKey = nuvioPlugins.tvdbApiKey()
        if (tmdbApiKey.isBlank() && tvdbApiKey.isBlank()) {
          posterEnrichmentKeys.removeAll(candidateKeys)
          posterFailureRequests.removeAll(candidateKeys)
          return@launch
        }

        val results = coroutineScope {
          candidates.map { item ->
            async {
              posterArtworkSemaphore.withPermit {
                val itemKey = catalogItemIdentityKey(item)
                val replaceFailedPoster = itemKey in forcePosterKeys
                val lookupItem = if (replaceFailedPoster) item.copy(posterUrl = null) else item
                val tmdbArtwork = if (tmdbApiKey.isBlank()) null else try {
                  nuvioMetadata.loadPosterArtwork(lookupItem, tmdbApiKey)
                } catch (error: CancellationException) {
                  throw error
                } catch (_: Exception) {
                  null
                }
                val tvdbArtwork = if (item.type == MediaType.TV && tvdbApiKey.isNotBlank() && tmdbArtwork?.posterUrl.isNullOrBlank()) {
                  val baseItem = tmdbArtwork ?: lookupItem
                  try {
                    tvdbMetadata.loadTitleArtwork(baseItem, tvdbApiKey)
                  } catch (error: CancellationException) {
                    throw error
                  } catch (_: Exception) {
                    null
                  }
                } else null
                when {
                  tmdbArtwork != null && tvdbArtwork != null -> mergeMissingCatalogArtwork(
                    tmdbArtwork,
                    tvdbArtwork,
                    replaceFailedPoster = replaceFailedPoster,
                  )
                  tvdbArtwork != null -> tvdbArtwork
                  else -> tmdbArtwork
                }
              }
            }
          }.awaitAll()
        }
        val artworkByKey = candidates.zip(results).mapNotNull { (item, artwork) ->
          val itemKey = catalogItemIdentityKey(item)
          artwork?.let {
            itemKey to mergeMissingCatalogArtwork(
              item,
              it,
              replaceFailedPoster = itemKey in forcePosterKeys,
            )
          }
        }.filter { (_, artwork) -> !artwork.posterUrl.isNullOrBlank() || !artwork.backdropUrl.isNullOrBlank() }
          .toMap()
        if (artworkByKey.isEmpty() || posterEnrichmentQuery != normalizedQuery || _state.value.query.trim() != normalizedQuery) return@launch

        val applyArtwork: (MediaItem) -> MediaItem = { item ->
          val itemKey = catalogItemIdentityKey(item)
          artworkByKey[itemKey]?.let {
            mergeMissingCatalogArtwork(item, it, replaceFailedPoster = itemKey in forcePosterKeys)
          } ?: item
        }
        _state.update { current ->
          if (current.query.trim() != normalizedQuery) current
          else current.copy(
            items = current.items.map(applyArtwork),
            recentItems = current.recentItems.map(applyArtwork),
            selectedItem = current.selectedItem?.let(applyArtwork),
          )
        }
        if (normalizedQuery.isBlank() && _state.value.query.isBlank()) {
          cachedHomeItems = cachedHomeItems.map(applyArtwork)
        }
      } finally {
        posterEnrichmentJobs.remove(enrichmentJob)
      }
    }
    posterEnrichmentJobs += enrichmentJob
    enrichmentJob.start()
  }

  private suspend fun migrateNuvioScraperRepositories() {
    val candidates = settings.catalogSources()
      .filterNot { it.manifestUrl.contains("v3-cinemeta.strem.io", true) || it.manifestUrl.contains("anime-kitsu.strem.fun", true) }
      .filter { checkedNuvioMigrationUrls.add(it.manifestUrl) }
    if (candidates.isEmpty()) return
    val migrated = mutableListOf<CatalogSource>()
    candidates.forEach { source ->
      if (catalogRepository.isNuvioScraperManifest(source.manifestUrl) && nuvioPlugins.importIfNuvioRepository(source.manifestUrl)) {
        migrated += source
      }
    }
    if (migrated.isEmpty()) return
    val remaining = settings.catalogSources().filterNot { source -> migrated.any { it.manifestUrl.equals(source.manifestUrl, true) } }
    settings.saveCatalogSources(remaining)
    _catalogSources.value = remaining
    if (_state.value.query.isBlank()) loadTrending() else runSearch(_state.value.query.trim())
    Log.i(TAG, "Migrated ${migrated.size} legacy provider repository configuration(s) into Nuvio scraper storage")
  }

  private fun normalizeCatalogManifestUrl(rawValue: String): String {
    val raw = rawValue.trim().substringBefore('#')
    require(raw.isNotBlank()) { "Enter a catalog add-on URL." }
    val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
    val path = withScheme.substringBefore('?').trimEnd('/')
    val query = withScheme.substringAfter('?', "")
    val manifest = if (path.endsWith("/manifest.json", true)) path else "$path/manifest.json"
    val uri = java.net.URI(manifest)
    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) { "Enter a valid HTTP(S) catalog add-on URL." }
    return if (query.isBlank()) manifest else "$manifest?$query"
  }

  private fun stableCatalogKey(item: MediaItem): String =
    "${item.catalogSourceId.orEmpty()}:${item.catalogId.orEmpty()}:${item.providerId ?: item.id}"

  private fun historyKey(item: MediaItem): String =
    "${stableCatalogKey(item)}:${item.historySeason ?: 0}:${item.historyEpisode ?: 0}"

  private fun reconcileHistory(items: List<MediaItem>): List<MediaItem> =
    reconcileHistory(items, advancedPreferences.enableRecentlyPlayed.get())

  private fun reconcileHistory(items: List<MediaItem>, enabled: Boolean): List<MediaItem> {
    val byKey = items.associateBy(::stableCatalogKey)
    val storedKeys =
      StreamWatchHistory.keysForDisplay(
        enabled = enabled,
        order = historyPrefs.getString(StreamWatchHistory.ORDER_KEY, "").orEmpty(),
        fallbackKeys = historyPrefs.getStringSet(StreamWatchHistory.KEYS_KEY, emptySet()).orEmpty(),
      )
    return storedKeys.mapNotNull { key ->
      val parts = key.split(":")
      if (parts.size < 3) return@mapNotNull null
      val baseKey = parts.dropLast(2).joinToString(":")
      val item = byKey[baseKey] ?: return@mapNotNull null
      item.copy(
        historySeason = parts.getOrNull(parts.lastIndex - 1)?.toIntOrNull()?.takeIf { it > 0 },
        historyEpisode = parts.lastOrNull()?.toIntOrNull()?.takeIf { it > 0 },
      ).let { history -> history.copy(
        historyStillUrl = history.historySeason?.let { season ->
          history.seasons.firstOrNull { it.number == season }?.episodes?.firstOrNull { it.number == history.historyEpisode }?.stillUrl
        },
        historyProgress = historyProgressPrefs.getFloat(historyProgressKey(history, history.historySeason, history.historyEpisode), 0f),
      ) }
    }.take(20)
  }

  private fun historyProgressKey(item: MediaItem, season: Int?, episode: Int?): String =
    "${stableCatalogKey(item)}:${season ?: 0}:${episode ?: 0}"
}
