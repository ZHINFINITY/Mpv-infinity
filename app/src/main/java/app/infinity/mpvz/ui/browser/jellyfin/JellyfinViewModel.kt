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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
  private val profileStore = JellyfinProfileStore(application)

  private fun JellyfinProfile.toServerProfile() = JellyfinServerProfile(id, name, serverUrl, userId, username, accessToken)

  private val _uiState = MutableStateFlow(JellyfinUiState())
  val uiState: StateFlow<JellyfinUiState> = _uiState.asStateFlow()

  private var httpClient: OkHttpClient? = null
  private var searchJob: Job? = null
  private var searchGeneration = 0L

  private fun groupShows(items: List<JellyfinTrack>): List<JellyfinTrack> {
    return items
      .filter { it.mediaType.equals("Series", ignoreCase = true) || it.mediaType.equals("Episode", ignoreCase = true) }
      .groupBy { item ->
        item.album.trim().takeIf { it.isNotBlank() }
          ?: item.title.substringBefore(" - ").substringBefore(" — ").trim()
      }
      .values
      .map { group -> group.firstOrNull { it.mediaType.equals("Series", ignoreCase = true) } ?: group.first() }
  }

  init {
    restoreSavedSession()
  }

  fun setHttpClient(client: OkHttpClient) {
    httpClient = client
    val session = _uiState.value.session
    if (session != null && _uiState.value.libraries.isEmpty()) {
      viewModelScope.launch { loadHome(session) }
    }
  }

  private fun restoreSavedSession() {
    val serverUrl = prefs.getString("server_url", null)
    val accessToken = prefs.getString("access_token", null)
    val userId = prefs.getString("user_id", null)
    val username = prefs.getString("username", null)

    if (!serverUrl.isNullOrBlank() && !accessToken.isNullOrBlank() && !userId.isNullOrBlank()) {
      val savedProfiles = profileStore.getAll()
      val activeProfile = savedProfiles.firstOrNull { it.serverUrl == serverUrl && it.userId == userId }
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
          activeServer = activeProfile?.toServerProfile() ?: profile,
          servers = (savedProfiles.map { it.toServerProfile() } + profile).distinctBy { it.id },
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
    profileName: String = "",
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
            name = profileName.ifBlank { username },
            serverUrl = session.serverUrl,
            userId = session.userId,
            username = username,
            accessToken = session.accessToken,
          )
          profileStore.upsert(JellyfinProfile(profile.id, profile.name, profile.serverUrl, profile.username, profile.userId, profile.accessToken))
          val savedProfiles = profileStore.getAll().map { it.toServerProfile() }
          _uiState.update {
            it.copy(
              session = session,
              activeServer = profile,
              servers = savedProfiles,
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
    profileName: String = "",
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
        name = profileName.ifBlank { username },
        serverUrl = session.serverUrl,
        userId = username,
        username = username,
        accessToken = token,
      )
      profileStore.upsert(JellyfinProfile(profile.id, profile.name, profile.serverUrl, profile.username, profile.userId, profile.accessToken))
      val savedProfiles = profileStore.getAll().map { it.toServerProfile() }
      _uiState.update {
        it.copy(
          session = session,
          activeServer = profile,
          servers = savedProfiles,
          isAuthenticating = false,
        )
      }
      saveSession(session, username)
      loadHome(session)
      onResult(true)
    }
  }

  fun switchServer(profile: JellyfinServerProfile) {
    val session = JellyfinSession(profile.serverUrl, profile.userId, profile.accessToken)
    _uiState.update {
      it.copy(session = session, activeServer = profile, currentItems = emptyList(), libraries = emptyList(), openLibrary = null, selectedLibraryId = null, error = null)
    }
    saveSession(session, profile.username.ifBlank { profile.name })
    viewModelScope.launch { loadHome(session) }
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
              heroItems = videos.filter { it.mediaType.equals("Movie", ignoreCase = true) || it.mediaType.equals("Series", ignoreCase = true) }.take(5),
              latestMovies = videos.filter { it.mediaType.equals("Movie", ignoreCase = true) }.take(20),
              latestShows = groupShows(videos).take(20),
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
    viewModelScope.launch { loadHome(session) }
  }

  fun refresh() {
    val session = _uiState.value.session ?: return
    val libraryId = _uiState.value.selectedLibraryId
    if (libraryId != null) loadLibrary(libraryId) else viewModelScope.launch { loadHome(session) }
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
    val normalizedQuery = query.trim()
    searchJob?.cancel()
    searchGeneration += 1
    val generation = searchGeneration
    val session = _uiState.value.session ?: return
    val client = httpClient ?: return
    if (normalizedQuery.isBlank()) {
      _uiState.update { it.copy(currentItems = emptyList(), searchQuery = "", isLoading = false, error = null) }
      return
    }
    searchJob = viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, searchQuery = query, error = null, openLibrary = null) }
      delay(250L)
      val jellyfin = JellyfinClient(client, getApplication())
      withContext(Dispatchers.IO) {
        jellyfin.search(session = session, query = normalizedQuery, limit = 50).fold(
          onSuccess = { items ->
            if (generation == searchGeneration) {
              _uiState.update { it.copy(currentItems = items, isLoading = false, error = null, openLibrary = null) }
            }
          },
          onFailure = { e ->
            if (generation == searchGeneration) {
              _uiState.update { it.copy(isLoading = false, error = e.message ?: "Search failed") }
            }
          },
        )
      }
    }
  }

  fun playItem(context: Context, track: JellyfinTrack) {
    val session = _uiState.value.session ?: return
    val client = httpClient ?: return
    val jellyfin = JellyfinClient(client, context)
    val streamUrl = jellyfin.getStreamUrl(session, track.id, isVideo = track.isVideo)
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

  fun openDetail(track: JellyfinTrack) {
    _uiState.update { it.copy(detailItem = track) }
  }

  fun closeDetail() {
    _uiState.update { it.copy(detailItem = null) }
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
