package app.infinity.mpvz.catalog

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.ViewModelProvider
import app.infinity.mpvz.catalog.nuvio.PluginRepository

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "MpvCatalogDiag"
    fun Factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
          CatalogViewModel(application) as T
      }
  }
  private val settings = CatalogSettings(application)
  private val _resolvers = MutableStateFlow(settings.resolvers())
  val resolvers: StateFlow<List<ResolverEndpoint>> = _resolvers.asStateFlow()
  private val _catalogSources = MutableStateFlow(settings.catalogSources())
  val catalogSources: StateFlow<List<CatalogSource>> = _catalogSources.asStateFlow()
  private val animeRepository = KitsuAnimeRepository()
  private val cinemetaRepository = CinemetaCatalogRepository()
  private val stremioRepository = StremioCatalogRepository()
  private val resolver = CloudStreamResolver(settings)
  private val nuvioPlugins = PluginRepository(application)
  private val nuvioMetadata = app.infinity.mpvz.catalog.nuvio.TmdbMetadataRepository()
  private val checkedNuvioMigrationUrls = mutableSetOf<String>()
  private val _state = MutableStateFlow(CatalogState())
  val state: StateFlow<CatalogState> = _state.asStateFlow()
  private var searchJob: Job? = null
  private var homeLoadJob: Job? = null
  // Keep the last successful home result so closing search does not refetch every addon rail.
  private var cachedHomeItems: List<MediaItem> = emptyList()
  private val _playbackStream = MutableStateFlow<StreamOption?>(null)
  val playbackStream: StateFlow<StreamOption?> = _playbackStream.asStateFlow()
  val autoChooseBestTorrent: Boolean get() = settings.autoChooseBestTorrent

  init {
    Log.i(TAG, "init sources=${_catalogSources.value.map { "${it.id}:${it.isEnabled}" }} resolvers=${_resolvers.value.map { "${it.baseUrl}:${it.enabled}" }}")
    syncCatalogResolvers(_catalogSources.value)
    loadTrending()
    viewModelScope.launch { migrateNuvioScraperRepositories() }
  }

  fun setQuery(query: String) {
    // Do not trim while the user is typing: a trailing space is the first character
    // of the next word and trimming it makes multi-word searches impossible.
    val normalized = query
    Log.i(TAG, "setQuery rawLength=${query.length} normalized=\"$normalized\"")
    // Do not render the previous search while the home catalog is being restored.
    searchJob?.cancel()
    homeLoadJob?.cancel()
    if (normalized.isBlank() && cachedHomeItems.isNotEmpty()) {
      // Restore the existing home rails immediately; explicit refresh still refetches them.
      _state.update {
        it.copy(
          query = normalized,
          items = cachedHomeItems,
          isLoading = false,
          catalogPage = 1,
          canLoadMore = cachedHomeItems.isNotEmpty(),
          error = null,
        )
      }
    } else {
      _state.update { it.copy(query = normalized, items = emptyList(), isLoading = true, error = null) }
      searchJob = viewModelScope.launch {
        delay(200)
        if (normalized.isBlank()) loadTrending() else runSearch(normalized)
      }
    }
  }

  fun toggleProvider(provider: CatalogProvider) {
    _state.update {
      val next = it.enabledProviders.toMutableSet().apply { if (!add(provider)) remove(provider) }
      it.copy(enabledProviders = next)
    }
    if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query) }
  }

  fun enableAllProviders() {
    _state.update { it.copy(enabledProviders = CatalogProvider.entries.toSet()) }
    if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query) }
  }

  fun loadMore() {
    val current = _state.value
    if (current.isLoadingMore || !current.canLoadMore) return
    viewModelScope.launch {
      _state.update { it.copy(isLoadingMore = true) }
      val nextPage = current.catalogPage + 1
      val result = runCatching { loadFromProviders(current.query.takeIf { it.isNotBlank() }, nextPage) }
      result.exceptionOrNull()?.let { error -> Log.e(TAG, "loadMore failed page=$nextPage query=${current.query}", error) }
      val more = result.getOrDefault(emptyList())
      val merged = (current.items + more).distinctBy { item -> "${item.catalogSourceId ?: item.provider}:${item.catalogId ?: ""}:${item.providerId ?: item.id}" }
      val added = merged.size > current.items.size
      _state.update {
        it.copy(
          items = merged,
          catalogPage = if (result.isSuccess) nextPage else current.catalogPage,
          // An empty page or a failed page must stop the near-end observer from
          // immediately starting the same request forever (and leaving its spinner visible).
          canLoadMore = result.isSuccess && added && more.isNotEmpty(),
          isLoadingMore = false,
          error = result.exceptionOrNull()?.message,
        )
      }
    }
  }

  fun openDetails(item: MediaItem) {
    showDetails(item)
  }

  fun reloadAddonConfiguration() {
    val sources = settings.catalogSources()
    val changed = sources != _catalogSources.value
    _catalogSources.value = sources
    syncCatalogResolvers(sources)
    if (changed) {
      if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query) }
    }
    viewModelScope.launch { migrateNuvioScraperRepositories() }
  }

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
      val metadata = runCatching { nuvioMetadata.load(item, nuvioPlugins.tmdbApiKey()) }.getOrNull()
        ?: runCatching { resolver.loadMetadata(item) }.getOrNull()
        ?: item
      val completedItem = if (metadata.type == MediaType.TV && metadata.seasons.isEmpty()) {
        val seasons = runCatching { resolver.loadSeasons(metadata) }.getOrDefault(emptyList())
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
      _state.update { it.copy(resolvingId = item.id, error = null, selectedSeason = season, selectedEpisode = episode) }
      runCatching { nuvioPlugins.resolve(item, season, episode) }
        .onSuccess { streams ->
          val directHttpsStreams = streams
            .filter { it.isPlayable && it.url.startsWith("https://", ignoreCase = true) && !it.isExternal }
            .distinctBy { it.url }
          _state.update {
            it.copy(
              streamOptions = directHttpsStreams,
              streamTitle = item.title,
              error = if (directHttpsStreams.isEmpty()) "The enabled Nuvio providers returned no direct HTTPS video links for this title or episode." else null,
            )
          }
        }
        .onFailure { error -> _state.update { it.copy(error = error.message ?: "Unable to resolve stream") } }
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
    _state.update { it.copy(selectedSeason = season, streamOptions = emptyList(), error = null) }
  }

  fun dismissStreams() { _state.update { it.copy(streamOptions = emptyList(), streamTitle = null) } }
  fun closeDetails() { _state.update { it.copy(streamOptions = emptyList(), streamTitle = null, selectedItem = null, selectedSeason = null, selectedEpisode = null) } }
  fun setSourceFilter(filter: String) { _state.update { it.copy(sourceFilter = filter) } }
  fun setSourceSort(sort: String) { _state.update { it.copy(sourceSort = sort) } }
  fun saveSettings(resolvers: List<ResolverEndpoint>, resolverToken: String, resolverPath: String) {
    saveResolvers(resolvers)
    val existingSources = settings.catalogSources().filterNot { it.id.startsWith("resolver-") }
    val resolverSources = resolvers.filter { isHttpAddonEndpoint(it.baseUrl) }.map { endpoint ->
      val baseUrl = endpoint.baseUrl.trimEnd('/')
      val manifestUrl = "$baseUrl/manifest.json"
      val name = baseUrl.substringAfter("://").substringBefore('/').ifBlank { "Addon catalog" }
      CatalogSource(
        id = "resolver-${manifestUrl.hashCode()}",
        name = name,
        manifestUrl = manifestUrl,
        isEnabled = endpoint.enabled,
      )
    }
    val synchronizedSources = (existingSources + resolverSources).distinctBy { it.manifestUrl.lowercase() }
    settings.saveCatalogSources(synchronizedSources)
    _catalogSources.value = synchronizedSources
    settings.resolverToken = resolverToken
    settings.resolverPath = resolverPath
    loadTrending()
  }
  fun saveAutoChooseBestTorrent(value: Boolean) { settings.autoChooseBestTorrent = value }
  fun saveResolvers(value: List<ResolverEndpoint>) {
    settings.saveResolvers(value)
    _resolvers.value = settings.resolvers()
  }
  private fun syncCatalogResolvers(sources: List<CatalogSource>) {
    val catalogResolvers = sources
      .filterNot { it.id.startsWith("cinemeta-") || it.id == "kitsu-anime" }
      .filter { isHttpAddonEndpoint(it.manifestUrl) }
      .mapNotNull { source ->
        val baseUrl = source.manifestUrl.substringBefore('?').removeSuffix("/manifest.json")
        val query = source.manifestUrl.substringAfter('?', "")
        val configuredUrl = if (query.isBlank()) baseUrl else "$baseUrl?$query"
        configuredUrl.takeIf { isHttpAddonEndpoint(it) }?.let { ResolverEndpoint(it, source.isEnabled) }
      }
    val synchronized = catalogResolvers.distinctBy { it.baseUrl.lowercase() }
    if (synchronized != settings.resolvers()) saveResolvers(synchronized)
  }
  fun addResolverEndpoint(value: String) {
    val endpoint = value.trim().removeSuffix("/manifest.json")
    if (!isHttpAddonEndpoint(endpoint)) return
    val updatedResolvers = (settings.resolvers() + ResolverEndpoint(endpoint)).distinctBy { it.baseUrl.trimEnd('/') }
    saveResolvers(updatedResolvers)
    val manifestUrl = endpoint.let { url ->
      if (url.endsWith("manifest.json", ignoreCase = true)) url else url.trimEnd('/') + "/manifest.json"
    }
    val sourceId = "resolver-${manifestUrl.hashCode()}"
    val source = CatalogSource(sourceId, manifestUrl.substringAfter("://").substringBefore('/').ifBlank { "Resolver catalog" }, manifestUrl)
    saveCatalogSources((settings.catalogSources().filterNot { it.manifestUrl.equals(manifestUrl, ignoreCase = true) } + source).distinctBy { it.manifestUrl.lowercase() })
  }
  fun currentSettings(): ResolverSettings = ResolverSettings(settings.resolvers(), settings.resolverToken, settings.resolverPath)
  fun currentCatalogSources(): List<CatalogSource> = settings.catalogSources()
  fun saveCatalogSources(sources: List<CatalogSource>) {
    val validSources = sources.filter { isHttpAddonEndpoint(it.manifestUrl) }
    settings.saveCatalogSources(validSources)
    _catalogSources.value = validSources
    syncCatalogResolvers(validSources)
    viewModelScope.launch {
      val repository = stremioRepository
      val named = validSources.map { source ->
        if (!source.id.startsWith("cinemeta-") && source.id != "kitsu-anime") source.copy(name = repository.manifestName(source.manifestUrl) ?: source.name) else source
      }
      if (named != _catalogSources.value) {
        settings.saveCatalogSources(named)
        _catalogSources.value = named
      }
    }
    loadTrending()
  }
  private suspend fun migrateNuvioScraperRepositories() {
    val candidates = settings.catalogSources()
      .filterNot { it.id.startsWith("cinemeta-") || it.id == "kitsu-anime" }
      .filter { checkedNuvioMigrationUrls.add(it.manifestUrl) }
    if (candidates.isEmpty()) return
    val migrated = candidates.filter { nuvioPlugins.importIfNuvioRepository(it.manifestUrl) }
    if (migrated.isEmpty()) return
    val remaining = settings.catalogSources().filterNot { source -> migrated.any { it.manifestUrl.equals(source.manifestUrl, true) } }
    settings.saveCatalogSources(remaining)
    _catalogSources.value = remaining
    syncCatalogResolvers(remaining)
    if (_state.value.query.isBlank()) loadTrending() else runSearch(_state.value.query)
    Log.i(TAG, "Migrated ${migrated.size} Nuvio scraper repository URL(s) from Stremio catalogs")
  }

  fun retry() { if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query) } }
  suspend fun refreshAll() {
    searchJob?.cancel()
    homeLoadJob?.cancel()
    _state.update { it.copy(isLoading = true, error = null) }
    if (_state.value.query.isBlank()) {
      val items = loadFromProviders(null)
      cachedHomeItems = items
      _state.update { it.copy(items = items, isLoading = false, catalogPage = 1, canLoadMore = items.isNotEmpty()) }
    } else {
      runSearch(_state.value.query)
    }
  }

  private fun loadTrending() {
    homeLoadJob?.cancel()
    homeLoadJob = viewModelScope.launch {
      _state.update { it.copy(isLoading = true, error = null) }
      runCatching { loadFromProviders(null) }
        .onSuccess { items ->
          cachedHomeItems = items
          if (_state.value.query.isBlank()) _state.update { it.copy(items = items, isLoading = false, catalogPage = 1, canLoadMore = items.isNotEmpty()) }
        }
        .onFailure { error ->
          if (error is CancellationException) throw error
          _state.update { it.copy(isLoading = false, error = error.message) }
        }
    }
  }

  private suspend fun runSearch(query: String) {
    Log.i(TAG, "search start query=\"$query\"")
    _state.update { it.copy(isLoading = true, error = null) }
    runProgressiveSearch(query)
  }

  private suspend fun runProgressiveSearch(query: String) = coroutineScope {
    val providers = _state.value.enabledProviders
    val enabledSources = settings.catalogSources().filter { it.isEnabled }
    val jobs = mutableListOf<Job>()

    fun publish(items: List<MediaItem>) {
      if (_state.value.query != query || items.isEmpty()) return
      _state.update { state ->
        val merged = (state.items + items).distinctBy { "${it.catalogSourceId ?: it.provider}:${it.catalogId ?: ""}:${it.providerId ?: it.id}" }
        state.copy(items = merged, catalogPage = 1, canLoadMore = merged.isNotEmpty())
      }
    }

    if (CatalogProvider.CINEMETA in providers && enabledSources.any { it.id.startsWith("cinemeta-") }) {
      jobs += launch {
        runCatching { cinemetaRepository.search(query) }
          .onSuccess { publish(it) }
          .onFailure { if (it !is CancellationException) Log.w(TAG, "Cinemeta search failed: ${it.message}") }
      }
    }
    if (CatalogProvider.KITSU in providers && enabledSources.any { it.id == "kitsu-anime" }) {
      jobs += launch {
        runCatching { animeRepository.popular(query) }
          .onSuccess { publish(it) }
          .onFailure { if (it !is CancellationException) Log.w(TAG, "Kitsu search failed: ${it.message}") }
      }
    }
    enabledSources.filter { !it.id.startsWith("cinemeta-") && it.id != "kitsu-anime" }.forEach { source ->
      jobs += launch {
        val items = withTimeoutOrNull(30_000L) {
          runCatching { stremioRepository.load(source, query) }
            .onFailure { if (it !is CancellationException) Log.w(TAG, "Catalog search failed source=${source.id}: ${it.message}") }
            .getOrDefault(emptyList())
        }.orEmpty()
        publish(items)
      }
    }
    jobs.joinAll()
    if (_state.value.query == query) {
      _state.update { it.copy(isLoading = false, catalogPage = 1, canLoadMore = it.items.isNotEmpty()) }
      Log.i(TAG, "search complete query=\"$query\" items=${_state.value.items.size}")
    }
  }

  private suspend fun loadFromProviders(query: String?, page: Int = 1): List<MediaItem> {
    val providers = _state.value.enabledProviders
    val enabledSources = settings.catalogSources().filter { it.isEnabled }
    Log.i(TAG, "load query=${query ?: "<home>"} providers=$providers sources=${enabledSources.map { it.id }}")
    val sources = enabledSources.map { it.id }.toSet()
    val (cinemeta, anime, custom) = coroutineScope {
      val cinemetaJob = async {
        if (CatalogProvider.CINEMETA in providers && sources.any { it.startsWith("cinemeta-") }) {
          runCatching { if (query.isNullOrBlank()) cinemetaRepository.popular(page) else cinemetaRepository.search(query, page) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        } else emptyList()
      }
      val animeJob = async {
        if (CatalogProvider.KITSU in providers && "kitsu-anime" in sources) {
          runCatching { animeRepository.popular(query, page) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        } else emptyList()
      }
      val customJob = async {
        enabledSources.filter { !it.id.startsWith("cinemeta-") && it.id != "kitsu-anime" }
          .map { source ->
            async {
              val items = withTimeoutOrNull(30_000L) {
                runCatching { stremioRepository.load(source, query, page) }.onFailure { error ->
                  if (error is CancellationException) throw error
                  Log.e("MpvCatalogDiag", "custom source failed source=${source.id} message=${error.message}", error)
                }.getOrDefault(emptyList())
              }
              if (items == null) Log.w("MpvCatalogDiag", "custom source timed out source=${source.id} query=${query ?: "<home>"}")
              items.orEmpty()
            }
          }.awaitAll().flatten()
      }
      Triple(cinemetaJob.await(), animeJob.await(), customJob.await())
    }
    val result = (cinemeta + anime + custom).distinctBy { "${it.catalogSourceId ?: it.provider}:${it.catalogId ?: ""}:${it.providerId ?: it.id}" }
    Log.i(TAG, "load complete query=${query ?: "<home>"} cinemeta=${cinemeta.size} anime=${anime.size} custom=${custom.size} total=${result.size}")
    return result
  }
}

data class ResolverSettings(val resolvers: List<ResolverEndpoint>, val resolverToken: String, val resolverPath: String)
