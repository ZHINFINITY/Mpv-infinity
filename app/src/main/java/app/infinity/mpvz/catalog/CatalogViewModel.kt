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

  fun resolve(item: MediaItem) {
    viewModelScope.launch {
      _state.update { it.copy(resolvingId = item.id, error = null) }
      runCatching { resolver.resolve(item).url }
        .onSuccess { _resolvedUrl.value = it }
        .onFailure { _state.update { state -> state.copy(error = it.message ?: "Unable to resolve stream") } }
      _state.update { it.copy(resolvingId = null) }
    }
  }

  fun consumeResolvedUrl() { _resolvedUrl.value = null }
  fun saveSettings(tmdbKey: String, resolverUrl: String, resolverToken: String) {
    settings.tmdbApiKey = tmdbKey
    settings.resolverBaseUrl = resolverUrl
    settings.resolverToken = resolverToken
    loadTrending()
  }
  fun currentSettings(): Triple<String, String, String> = Triple(settings.tmdbApiKey, settings.resolverBaseUrl, settings.resolverToken)

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
