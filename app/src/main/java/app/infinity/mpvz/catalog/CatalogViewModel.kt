package app.infinity.mpvz.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider

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
  private val repository = TmdbCatalogRepository(settings)
  private val resolver = CloudStreamResolver(settings)
  private val _state = MutableStateFlow(CatalogState())
  val state: StateFlow<CatalogState> = _state.asStateFlow()
  private var searchJob: Job? = null
  private val _resolvedUrl = MutableStateFlow<String?>(null)
  val resolvedUrl: StateFlow<String?> = _resolvedUrl.asStateFlow()

  init { loadTrending() }

  fun setQuery(query: String) {
    _state.update { it.copy(query = query, error = null) }
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      delay(350)
      if (query.isBlank()) loadTrending() else runSearch(query)
    }
  }

  fun openDetails(item: MediaItem) {
    viewModelScope.launch {
      _state.update { it.copy(resolvingId = item.id, selectedItem = item, error = null) }
      runCatching {
        val identifiedItem = repository.details(item)
        identifiedItem
      }
        .onSuccess { identifiedItem ->
          _state.update { it.copy(selectedItem = identifiedItem, error = null) }
        }
        .onFailure { _state.update { state -> state.copy(error = it.message ?: "Unable to resolve stream") } }
      _state.update { it.copy(resolvingId = null) }
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
  fun consumeResolvedUrl() { _resolvedUrl.value = null }
  fun saveSettings(tmdbKey: String, resolverUrl: String, resolverToken: String, resolverPath: String) {
    settings.tmdbApiKey = tmdbKey
    settings.resolverBaseUrl = resolverUrl
    settings.resolverToken = resolverToken
    settings.resolverPath = resolverPath
    loadTrending()
  }
  fun currentSettings(): Quadruple = Quadruple(settings.tmdbApiKey, settings.resolverBaseUrl, settings.resolverToken, settings.resolverPath)

  private fun loadTrending() {
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true, error = null) }
      runCatching { repository.trending() }
        .onSuccess { items -> _state.update { it.copy(items = items, isLoading = false) } }
        .onFailure { error -> _state.update { it.copy(isLoading = false, error = error.message) } }
    }
  }

  private suspend fun runSearch(query: String) {
    _state.update { it.copy(isLoading = true) }
    runCatching { repository.search(query) }
      .onSuccess { items -> _state.update { it.copy(items = items, isLoading = false) } }
      .onFailure { error -> _state.update { it.copy(isLoading = false, error = error.message) } }
  }
}

data class Quadruple(val tmdbKey: String, val resolverUrl: String, val resolverToken: String, val resolverPath: String)
