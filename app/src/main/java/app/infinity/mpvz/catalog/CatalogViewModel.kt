package app.infinity.mpvz.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.lifecycle.ViewModelProvider

data class TorrentLaunchRequest(val item: MediaItem, val stream: StreamOption, val streams: List<StreamOption> = listOf(stream), val season: Int? = null, val episode: Int? = null)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
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
  private val resolver = CloudStreamResolver(settings)
  private val _state = MutableStateFlow(CatalogState())
  val state: StateFlow<CatalogState> = _state.asStateFlow()
  private var searchJob: Job? = null
  private val _resolvedUrl = MutableStateFlow<String?>(null)
  val resolvedUrl: StateFlow<String?> = _resolvedUrl.asStateFlow()
  private val _torrentLaunch = MutableSharedFlow<TorrentLaunchRequest>(extraBufferCapacity = 1)
  val torrentLaunch: SharedFlow<TorrentLaunchRequest> = _torrentLaunch
  val autoChooseBestTorrent: Boolean get() = settings.autoChooseBestTorrent

  init {
    loadTrending()
    viewModelScope.launch {
      val repository = StremioCatalogRepository()
      val named = _catalogSources.value.map { source ->
        if (source.id.startsWith("custom-")) source.copy(name = repository.manifestName(source.manifestUrl) ?: source.name) else source
      }
      if (named != _catalogSources.value) {
        settings.saveCatalogSources(named)
        _catalogSources.value = named
      }
    }
  }

  fun setQuery(query: String) {
    _state.update { it.copy(query = query, error = null) }
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      delay(350)
      if (query.isBlank()) loadTrending() else runSearch(query)
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
    if (current.isLoadingMore || !current.canLoadMore || false) return
    viewModelScope.launch {
      _state.update { it.copy(isLoadingMore = true) }
      val nextPage = current.catalogPage + 1
      val more = emptyList<MediaItem>()
      _state.update { it.copy(items = (it.items + more).distinctBy { item -> "${item.provider}:${item.providerId ?: item.id}" }, catalogPage = nextPage, canLoadMore = more.isNotEmpty(), isLoadingMore = false) }
    }
  }

  fun openDetails(item: MediaItem) {
    viewModelScope.launch {
      _state.update { it.copy(resolvingId = item.id, selectedItem = null, error = null) }
      _torrentLaunch.emit(TorrentLaunchRequest(item, StreamOption(url = "", title = "Loading"), emptyList()))
      _state.update { it.copy(resolvingId = null) }
    }
  }

  fun showDetails(item: MediaItem) {
    _state.update {
      it.copy(
        resolvingId = null,
        selectedItem = item,
        streamOptions = emptyList(),
        streamTitle = null,
        selectedSeason = null,
        selectedEpisode = null,
        error = null,
      )
    }
  }

  fun resolve(item: MediaItem, season: Int? = null, episode: Int? = null) {
    viewModelScope.launch {
      _state.update { it.copy(resolvingId = item.id, error = null, selectedSeason = season, selectedEpisode = episode) }
      runCatching { resolver.resolve(item, season, episode) }
        .onSuccess { streams -> _state.update { it.copy(streamOptions = streams, streamTitle = item.title, error = if (streams.isEmpty()) "Resolver returned no streams." else null) } }
        .onFailure { error -> _state.update { it.copy(error = error.message ?: "Unable to resolve stream") } }
      _state.update { it.copy(resolvingId = null) }
    }
  }

  fun playStream(stream: StreamOption) {
    _resolvedUrl.value = stream.url
    _state.update { it.copy(streamOptions = emptyList(), streamTitle = null) }
  }

  fun dismissStreams() { _state.update { it.copy(streamOptions = emptyList(), streamTitle = null) } }
  fun closeDetails() { _state.update { it.copy(streamOptions = emptyList(), streamTitle = null, selectedItem = null, selectedSeason = null, selectedEpisode = null) } }
  fun setSourceFilter(filter: String) { _state.update { it.copy(sourceFilter = filter) } }
  fun setSourceSort(sort: String) { _state.update { it.copy(sourceSort = sort) } }
  fun consumeResolvedUrl() { _resolvedUrl.value = null }
  fun saveSettings(resolvers: List<ResolverEndpoint>, resolverToken: String, resolverPath: String) {
    saveResolvers(resolvers)
    settings.resolverToken = resolverToken
    settings.resolverPath = resolverPath
    loadTrending()
  }
  fun saveAutoChooseBestTorrent(value: Boolean) { settings.autoChooseBestTorrent = value }
  fun saveResolvers(value: List<ResolverEndpoint>) {
    settings.saveResolvers(value)
    _resolvers.value = settings.resolvers()
  }
  fun currentSettings(): ResolverSettings = ResolverSettings(settings.resolvers(), settings.resolverToken, settings.resolverPath)
  fun currentCatalogSources(): List<CatalogSource> = settings.catalogSources()
  fun saveCatalogSources(sources: List<CatalogSource>) {
    settings.saveCatalogSources(sources)
    _catalogSources.value = sources
    viewModelScope.launch {
      val repository = StremioCatalogRepository()
      val named = sources.map { source ->
        if (source.id.startsWith("custom-")) source.copy(name = repository.manifestName(source.manifestUrl) ?: source.name) else source
      }
      if (named != _catalogSources.value) {
        settings.saveCatalogSources(named)
        _catalogSources.value = named
      }
    }
    loadTrending()
  }
  fun retry() { if (_state.value.query.isBlank()) loadTrending() else viewModelScope.launch { runSearch(_state.value.query) } }
  suspend fun refreshAll() {
    searchJob?.cancel()
    if (_state.value.query.isBlank()) loadFromProviders(null).also { items -> _state.update { it.copy(items = items, catalogPage = 1, canLoadMore = items.isNotEmpty()) } }
    else runSearch(_state.value.query)
  }

  private fun loadTrending() {
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true, error = null) }
      runCatching { loadFromProviders(null) }
        .onSuccess { items -> _state.update { it.copy(items = items, isLoading = false, catalogPage = 1, canLoadMore = items.isNotEmpty()) } }
        .onFailure { error -> _state.update { it.copy(isLoading = false, error = error.message) } }
    }
  }

  private suspend fun runSearch(query: String) {
    _state.update { it.copy(isLoading = true) }
    runCatching { loadFromProviders(query) }
      .onSuccess { items -> _state.update { it.copy(items = items, isLoading = false, catalogPage = 1, canLoadMore = items.isNotEmpty()) } }
      .onFailure { error -> _state.update { it.copy(isLoading = false, error = error.message) } }
  }

  private suspend fun loadFromProviders(query: String?): List<MediaItem> {
    val providers = _state.value.enabledProviders
    val enabledSources = settings.catalogSources().filter { it.isEnabled }
    val sources = enabledSources.map { it.id }.toSet()
    val (cinemeta, anime, custom) = coroutineScope {
      val cinemetaJob = async {
        if (CatalogProvider.CINEMETA in providers && sources.any { it.startsWith("cinemeta-") }) {
          runCatching { if (query.isNullOrBlank()) cinemetaRepository.popular() else cinemetaRepository.search(query) }.getOrDefault(emptyList())
        } else emptyList()
      }
      val animeJob = async {
        if (CatalogProvider.KITSU in providers && "kitsu-anime" in sources) {
          runCatching { animeRepository.popular(query) }.getOrDefault(emptyList())
        } else emptyList()
      }
      val customJob = async {
      enabledSources.filter { it.id.startsWith("custom-") }.map { source ->
        async { runCatching { StremioCatalogRepository().load(source, query) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
      }
      Triple(cinemetaJob.await(), animeJob.await(), customJob.await())
    }
    return (cinemeta + anime + custom).distinctBy { "${it.catalogSourceId ?: it.provider}:${it.providerId ?: it.id}" }
  }
}

data class ResolverSettings(val resolvers: List<ResolverEndpoint>, val resolverToken: String, val resolverPath: String)
