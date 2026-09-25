package app.infinity.mpvz.catalog

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.infinity.mpvz.catalog.nuvio.PluginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
  private val metadataRepository = StremioMetadataRepository()
  private val nuvioPlugins = PluginRepository(application)
  private val nuvioMetadata = app.infinity.mpvz.catalog.nuvio.TmdbMetadataRepository()
  private val checkedNuvioMigrationUrls = mutableSetOf<String>()
  private val _state = MutableStateFlow(CatalogState())
  val state: StateFlow<CatalogState> = _state.asStateFlow()
  private val _playbackStream = MutableStateFlow<StreamOption?>(null)
  val playbackStream: StateFlow<StreamOption?> = _playbackStream.asStateFlow()

  private var searchJob: Job? = null
  private var homeLoadJob: Job? = null
  private var cachedHomeItems: List<MediaItem> = emptyList()

  init {
    loadTrending()
    viewModelScope.launch { migrateNuvioScraperRepositories() }
  }

  fun setQuery(query: String) {
    searchJob?.cancel()
    homeLoadJob?.cancel()
    _state.update { it.copy(query = query, items = emptyList(), isLoading = true, isLoadingMore = false, error = null, catalogPage = 1, canLoadMore = true) }
    searchJob = viewModelScope.launch {
      delay(SEARCH_DEBOUNCE_MS)
      if (_state.value.query != query) return@launch
      if (query.isBlank()) {
        val cached = cachedHomeItems
        if (cached.isNotEmpty()) {
          _state.update { it.copy(items = cached, isLoading = false, catalogPage = 1, canLoadMore = true) }
        } else {
          loadTrending()
        }
      } else {
        runSearch(query.trim())
      }
    }
  }

  fun loadMore() {
    val snapshot = _state.value
    if (snapshot.isLoadingMore || snapshot.isLoading || !snapshot.canLoadMore || snapshot.items.isEmpty()) return
    viewModelScope.launch {
      _state.update { it.copy(isLoadingMore = true, error = null) }
      val nextPage = snapshot.catalogPage + 1
      val result = runCatching { loadFromAddons(snapshot.query.takeIf(String::isNotBlank), nextPage) }
      val more = result.getOrDefault(emptyList())
      _state.update { current ->
        if (current.query != snapshot.query) current.copy(isLoadingMore = false)
        else {
          val merged = (current.items + more).distinctBy(::stableCatalogKey)
          current.copy(
            items = merged,
            catalogPage = if (result.isSuccess) nextPage else snapshot.catalogPage,
            canLoadMore = result.isSuccess && merged.size > current.items.size && more.isNotEmpty(),
            isLoadingMore = false,
            error = result.exceptionOrNull()?.let { redactAddonConfigurationFromLog(it.message.orEmpty()) },
          )
        }
      }
    }
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
      val source = CatalogSource(
        id = "catalog-${manifestUrl.hashCode().toUInt().toString(16)}",
        name = name,
        manifestUrl = manifestUrl,
      )
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

  fun showDetails(item: MediaItem) {
    _state.update {
      it.copy(
        resolvingId = item.id,
        selectedItem = item,
        streamOptions = emptyList(),
        streamTitle = null,
        selectedSeason = item.seasons.firstOrNull()?.number,
        selectedEpisode = null,
        error = null,
      )
    }
    viewModelScope.launch {
      val sources = settings.catalogSources()
      val addonMetadata = runCatching { metadataRepository.loadMetadata(item, sources) }.getOrNull() ?: item
      val metadata = runCatching { nuvioMetadata.load(addonMetadata, nuvioPlugins.tmdbApiKey()) }.getOrNull() ?: addonMetadata
      val completedItem = if (metadata.type == MediaType.TV && metadata.seasons.isEmpty()) {
        val seasons = runCatching { metadataRepository.loadSeasons(metadata, sources) }.getOrDefault(emptyList())
        if (seasons.isEmpty()) metadata else metadata.copy(seasons = seasons)
      } else metadata
      _state.update { current ->
        if (current.selectedItem?.id == item.id) current.copy(
          selectedItem = completedItem,
          selectedSeason = current.selectedSeason ?: completedItem.seasons.firstOrNull()?.number,
          resolvingId = null,
        ) else current
      }
    }
  }

  fun resolve(item: MediaItem, season: Int? = null, episode: Int? = null) {
    viewModelScope.launch {
      _state.update { it.copy(resolvingId = item.id, error = null, selectedSeason = season, selectedEpisode = episode, streamOptions = emptyList()) }
      runCatching { nuvioPlugins.resolve(item, season, episode) }
        .onSuccess { streams ->
          val directHttpsStreams = streams
            .filter { it.isPlayable && it.url.startsWith("https://", ignoreCase = true) && !it.isExternal }
            .distinctBy { it.url }
          _state.update { current ->
            current.copy(
              streamOptions = directHttpsStreams,
              streamTitle = item.title,
              error = if (directHttpsStreams.isEmpty()) {
                "The enabled Nuvio providers returned no direct HTTPS links. Check provider settings and confirm this title or episode is supported."
              } else null,
            )
          }
        }
        .onFailure { error ->
          if (error is CancellationException) throw error
          _state.update { it.copy(error = redactAddonConfigurationFromLog(error.message ?: "Unable to resolve direct HTTPS streams")) }
        }
      _state.update { it.copy(resolvingId = null) }
    }
  }

  fun playStream(stream: StreamOption) {
    if (!stream.url.startsWith("https://", ignoreCase = true) || !stream.isPlayable || stream.isExternal) return
    _playbackStream.value = stream
    _state.update { it.copy(streamOptions = emptyList(), streamTitle = null) }
  }

  fun consumePlaybackStream() { _playbackStream.value = null }

  fun selectSeason(season: Int) {
    _state.update { it.copy(selectedSeason = season, streamOptions = emptyList(), selectedEpisode = null, error = null) }
  }

  fun closeDetails() {
    _state.update { it.copy(streamOptions = emptyList(), streamTitle = null, selectedItem = null, selectedSeason = null, selectedEpisode = null, error = null) }
  }

  fun retry() {
    if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query.trim()) }
  }

  suspend fun refreshAll() {
    searchJob?.cancel()
    homeLoadJob?.cancel()
    if (_state.value.query.isBlank()) {
      val items = loadFromAddons(null)
      cachedHomeItems = items
      _state.update { it.copy(items = items, isLoading = false, isLoadingMore = false, catalogPage = 1, canLoadMore = items.isNotEmpty(), error = null) }
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
    homeLoadJob = viewModelScope.launch {
      val sources = settings.catalogSources().filter { it.isEnabled }
      if (sources.isEmpty()) {
        cachedHomeItems = emptyList()
        _state.update { it.copy(items = emptyList(), isLoading = false, isLoadingMore = false, canLoadMore = false, error = null) }
        return@launch
      }
      _state.update { it.copy(isLoading = true, isLoadingMore = false, error = null) }
      runCatching { loadFromAddons(null) }
        .onSuccess { items ->
          cachedHomeItems = items
          if (_state.value.query.isBlank()) _state.update { it.copy(items = items, isLoading = false, catalogPage = 1, canLoadMore = items.isNotEmpty()) }
        }
        .onFailure { error ->
          if (error is CancellationException) throw error
          _state.update { it.copy(isLoading = false, error = redactAddonConfigurationFromLog(error.message.orEmpty())) }
        }
    }
  }

  private suspend fun runSearch(query: String) {
    if (query.isBlank()) return
    _state.update { it.copy(isLoading = true, isLoadingMore = false, error = null, items = emptyList(), catalogPage = 1, canLoadMore = true) }
    val sources = settings.catalogSources().filter { it.isEnabled }
    if (sources.isEmpty()) {
      _state.update { it.copy(isLoading = false, canLoadMore = false) }
      return
    }
    val result = runCatching { loadFromAddons(query) }
    if (_state.value.query.trim() != query) return
    result.fold(
      onSuccess = { items -> _state.update { it.copy(items = items, isLoading = false, catalogPage = 1, canLoadMore = items.isNotEmpty(), error = null) } },
      onFailure = { error ->
        if (error is CancellationException) throw error
        _state.update { it.copy(items = emptyList(), isLoading = false, canLoadMore = false, error = redactAddonConfigurationFromLog(error.message.orEmpty())) }
      },
    )
  }

  private suspend fun loadFromAddons(query: String?, page: Int = 1): List<MediaItem> = coroutineScope {
    val sources = settings.catalogSources().filter { it.isEnabled }
    Log.i(TAG, "load queryLength=${query?.length ?: 0} catalogAddons=${sources.size} page=$page")
    val results = sources.map { source ->
      async {
        withTimeoutOrNull(45_000L) {
          runCatching { catalogRepository.load(source, query, page) }
            .onFailure { error ->
              if (error is CancellationException) throw error
              Log.w(TAG, "catalog source failed id=${source.id}: ${redactAddonConfigurationFromLog(error.message.orEmpty())}")
            }
            .getOrDefault(emptyList())
        }.orEmpty()
      }
    }.awaitAll().flatten().distinctBy(::stableCatalogKey)
    Log.i(TAG, "load complete queryLength=${query?.length ?: 0} items=${results.size}")
    results
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
}
