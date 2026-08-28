/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class JellyfinViewModel(
  application: Application,
) : AndroidViewModel(application) {

  private val prefs = application.getSharedPreferences("jellyfin_profiles", Context.MODE_PRIVATE)

  private val _uiState = MutableStateFlow(JellyfinUiState())
  val uiState: StateFlow<JellyfinUiState> = _uiState.asStateFlow()

  private var httpClient: OkHttpClient? = null

  init {
    restoreSavedSession()
  }

  fun setHttpClient(client: OkHttpClient) {
    httpClient = client
    val session = _uiState.value.session
    if (session != null && httpClient != null && _uiState.value.libraries.isEmpty()) {
      loadHome(session)
    }
  }

  private fun restoreSavedSession() {
    val serverUrl = prefs.getString("server_url", null)
    val accessToken = prefs.getString("access_token", null)
    val userId = prefs.getString("user_id", null)
    val username = prefs.getString("username", null)

    if (!serverUrl.isNullOrBlank() && !accessToken.isNullOrBlank() && !userId.isNullOrBlank()) {
      val session = JellyfinSession(
        serverUrl = serverUrl,
        userId = userId,
        accessToken = accessToken,
      )
      val profile = JellyfinServerProfile(
        id = userId,
        name = username ?: "Server",
        serverUrl = serverUrl,
        userId = userId,
        username = username ?: "",
        accessToken = accessToken,
      )
      _uiState.update {
        it.copy(
          session = session,
          activeServer = profile,
          servers = listOf(profile),
        )
      }
    }
  }

  private fun saveSession(session: JellyfinSession, username: String) {
    prefs.edit()
      .putString("server_url", session.serverUrl)
      .putString("access_token", session.accessToken)
      .putString("user_id", session.userId)
      .putString("username", username)
      .apply()
  }

  fun login(
    serverUrl: String,
    username: String,
    password: String,
    onResult: (Boolean) -> Unit,
  ) {
    val client = httpClient ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isAuthenticating = true, authError = null) }
      val jellyfin = JellyfinClient(client, getApplication())
      jellyfin.authenticate(serverUrl, username, password).fold(
        onSuccess = { session ->
          val profile = JellyfinServerProfile(
            id = session.userId,
            name = username,
            serverUrl = session.serverUrl,
            userId = session.userId,
            username = username,
            accessToken = session.accessToken,
          )
          _uiState.update {
            it.copy(
              session = session,
              activeServer = profile,
              servers = listOf(profile),
              isAuthenticating = false,
            )
          }
          saveSession(session, username)
          loadHome(session)
          onResult(true)
        },
        onFailure = { e ->
          _uiState.update { it.copy(isAuthenticating = false, authError = e.message ?: "Authentication failed") }
          onResult(false)
        },
      )
    }
  }

  fun loginWithToken(
    serverUrl: String,
    username: String,
    token: String,
    onResult: (Boolean) -> Unit,
  ) {
    val client = httpClient ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isAuthenticating = true, authError = null) }
      val session = JellyfinSession(
        serverUrl = JellyfinClient.normalizeUrl(serverUrl),
        userId = username,
        accessToken = token,
      )
      val profile = JellyfinServerProfile(
        id = username,
        name = username,
        serverUrl = session.serverUrl,
        userId = username,
        username = username,
        accessToken = token,
      )
      _uiState.update {
        it.copy(
          session = session,
          activeServer = profile,
          servers = listOf(profile),
          isAuthenticating = false,
        )
      }
      saveSession(session, username)
      loadHome(session)
      onResult(true)
    }
  }

  fun logout() {
    _uiState.update { JellyfinUiState() }
    prefs.edit().clear().apply()
  }

  private suspend fun loadHome(session: JellyfinSession) {
    _uiState.update { it.copy(isLoading = true, error = null) }
    val client = httpClient ?: return
    val jellyfin = JellyfinClient(client, getApplication())

    withContext(Dispatchers.IO) {
      jellyfin.loadLibraries(session).fold(
        onSuccess = { libs ->
          _uiState.update { it.copy(libraries = libs) }
          val allItems = mutableListOf<JellyfinTrack>()
          for (lib in libs) {
            jellyfin.loadMedia(
              session = session,
              parentId = lib.id,
              limit = 50,
              sortBy = "DateCreated",
              sortOrder = "Descending",
            ).fold(
              onSuccess = { items -> allItems.addAll(items) },
              onFailure = { },
            )
          }
          val videos = allItems.filter { it.isVideo }
          val audio = allItems.filter { !it.isVideo }
          _uiState.update { state ->
            state.copy(
              heroItems = videos.take(5),
              latestMovies = videos.filter { it.mediaType == "Movie" }.take(20),
              latestShows = videos.filter { it.mediaType == "Episode" }.take(20),
              latestMusic = audio.take(20),
              currentItems = allItems,
              isLoading = false,
            )
          }
        },
        onFailure = { e ->
          _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load libraries") }
        },
      )
    }
  }

  fun loadLibrary(libraryId: String) {
    val session = _uiState.value.session ?: return
    val client = httpClient ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, error = null) }
      val jellyfin = JellyfinClient(client, getApplication())
      val library = _uiState.value.libraries.find { it.id == libraryId }
      val libView = library?.let {
        JellyfinLibraryView(
          id = it.id,
          title = it.name,
          itemTypes = if (it.collectionType == "music") "Audio,MusicAlbum,MusicArtist" else "Movie,Episode,Series",
          collectionType = it.collectionType,
          isMusic = it.collectionType == "music",
        )
      }
      withContext(Dispatchers.IO) {
        jellyfin.loadMedia(
          session = session,
          parentId = libraryId,
          limit = 100,
          sortBy = _uiState.value.sortBy.apiValue,
          sortOrder = _uiState.value.sortOrder.apiValue,
        ).fold(
          onSuccess = { items ->
            _uiState.update {
              it.copy(
                currentItems = items,
                openLibrary = libView,
                selectedLibraryId = libraryId,
                isLoading = false,
              )
            }
          },
          onFailure = { e ->
            _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load library") }
          },
        )
      }
    }
  }

  fun navigateBack() {
    _uiState.update {
      it.copy(openLibrary = null, selectedLibraryId = null, currentItems = emptyList(), searchQuery = "")
    }
    val session = _uiState.value.session ?: return
    loadHome(session)
  }

  fun refresh() {
    val session = _uiState.value.session ?: return
    val libraryId = _uiState.value.selectedLibraryId
    if (libraryId != null) loadLibrary(libraryId) else loadHome(session)
  }

  fun loadMore() {
    val session = _uiState.value.session ?: return
    val libraryId = _uiState.value.selectedLibraryId ?: return
    val client = httpClient ?: return
    if (_uiState.value.isLoadingMore || !_uiState.value.hasMore) return
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingMore = true) }
      val jellyfin = JellyfinClient(client, getApplication())
      withContext(Dispatchers.IO) {
        jellyfin.loadMedia(
          session = session,
          parentId = libraryId,
          limit = 50,
          startIndex = _uiState.value.currentItems.size,
          sortBy = _uiState.value.sortBy.apiValue,
          sortOrder = _uiState.value.sortOrder.apiValue,
        ).fold(
          onSuccess = { newItems ->
            _uiState.update {
              it.copy(currentItems = it.currentItems + newItems, isLoadingMore = false, hasMore = newItems.isNotEmpty())
            }
          },
          onFailure = { _uiState.update { it.copy(isLoadingMore = false) } },
        )
      }
    }
  }

  fun setSort(sortBy: JellyfinSortBy, sortOrder: JellyfinSortOrder) {
    _uiState.update { it.copy(sortBy = sortBy, sortOrder = sortOrder) }
    val libraryId = _uiState.value.selectedLibraryId
    if (libraryId != null) loadLibrary(libraryId)
  }

  fun setSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
  }

  fun setMusicTab(tab: JellyfinMusicTab) {
    _uiState.update { it.copy(musicActiveTab = tab) }
  }

  fun setSearchCategory(category: JellyfinSearchCategory) {
    _uiState.update { it.copy(searchCategory = category) }
  }

  fun search(query: String) {
    val session = _uiState.value.session ?: return
    val client = httpClient ?: return
    if (query.isBlank()) {
      _uiState.update { it.copy(currentItems = emptyList(), searchQuery = "") }
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, searchQuery = query) }
      val jellyfin = JellyfinClient(client, getApplication())
      withContext(Dispatchers.IO) {
        jellyfin.search(session = session, query = query, limit = 50).fold(
          onSuccess = { items -> _uiState.update { it.copy(currentItems = items, isLoading = false, openLibrary = null) } },
          onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } },
        )
      }
    }
  }

  fun playItem(context: Context, track: JellyfinTrack) {
    val session = _uiState.value.session ?: return
    val client = httpClient ?: return
    val jellyfin = JellyfinClient(client, context)
    val streamUrl = jellyfin.getStreamUrl(session, track.id)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(streamUrl)).apply {
      setClass(context, Class.forName("app.infinity.mpvz.ui.player.PlayerActivity"))
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("internal_launch", true)
      putExtra("launch_source", "jellyfin")
      putExtra("is_audio", !track.isVideo)
      putExtra("title", track.title)
    }
    context.startActivity(intent)
  }

  fun playTracks(context: Context, tracks: List<JellyfinTrack>, index: Int) {
    if (tracks.isEmpty()) return
    val track = tracks[index.coerceIn(tracks.indices)]
    playItem(context, track)
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory {
      return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          return JellyfinViewModel(application) as T
        }
      }
    }
  }
}
