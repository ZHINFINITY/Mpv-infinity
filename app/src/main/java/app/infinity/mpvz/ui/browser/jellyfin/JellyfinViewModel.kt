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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

  private fun libraryItemTypes(collectionType: String?, libraryName: String = ""): String = when {
    libraryName.contains("anime", ignoreCase = true) ||
      collectionType.equals("tvshows", ignoreCase = true) ||
      libraryName.contains("tv", ignoreCase = true) ||
      libraryName.contains("show", ignoreCase = true) ||
      libraryName.contains("series", ignoreCase = true) -> "Series"
    collectionType.equals("movies", ignoreCase = true) ||
      libraryName.contains("movie", ignoreCase = true) ||
      libraryName.contains("film", ignoreCase = true) -> "Movie"
    collectionType.equals("music", ignoreCase = true) -> "Audio,MusicAlbum,MusicArtist"
    else -> "Movie,Series"
  }

  private fun mixedHeroItems(items: List<JellyfinTrack>, rotation: Int = 0): List<JellyfinTrack> {
    val movies = items.filter { it.mediaType.equals("Movie", ignoreCase = true) }.distinctBy { it.id }
    val series = items.filter { it.mediaType.equals("Series", ignoreCase = true) }.distinctBy { it.id }
    val mixed = buildList {
      for (index in 0 until maxOf(movies.size, series.size)) {
        movies.getOrNull(index)?.let(::add)
        series.getOrNull(index)?.let(::add)
      }
    }
    if (mixed.isEmpty()) return emptyList()
    // Advance one complete five-card page on each refresh so the next batch
    // does not repeat the previous five titles until the catalog is exhausted.
    val offset = (rotation * 5).mod(mixed.size)
    return (mixed.drop(offset) + mixed.take(offset)).take(5)
  }

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
      it.copy(session = session, activeServer = profile, currentItems = emptyList(), libraries = emptyList(), watchHistory = emptyList(), openLibrary = null, selectedLibraryId = null, error = null)
    }
    saveSession(session, profile.username.ifBlank { profile.name })
    viewModelScope.launch { loadHome(session) }
  }

  fun logout() {
    _uiState.update { JellyfinUiState() }
    prefs.edit().clear().apply()
  }

  private suspend fun loadHome(session: JellyfinSession, advanceHero: Boolean = false) {
    _uiState.update { it.copy(isLoading = true, error = null) }
    val client = httpClient ?: return
    val jellyfin = JellyfinClient(client, getApplication())

    withContext(Dispatchers.IO) {
      jellyfin.loadLibraries(session).fold(
        onSuccess = { libs ->
          val historyDeferred = async(Dispatchers.IO) {
            jellyfin.loadWatchHistory(session).getOrDefault(emptyList())
          }
          val mediaDeferred = async(Dispatchers.IO) {
            libs.map { lib ->
              async(Dispatchers.IO) {
                jellyfin.loadAllMedia(
                  session = session,
                  parentId = lib.id,
                  sortBy = "DateCreated",
                  sortOrder = "Descending",
                  includeItemTypes = libraryItemTypes(lib.collectionType, lib.name),
                  libraryName = lib.name,
                ).getOrDefault(emptyList())
              }
            }.awaitAll().flatten()
          }
          val watchHistory = historyDeferred.await()
          val allItems = mediaDeferred.await()
          val videos = allItems.filter { it.isVideo }
          val audio = allItems.filter { !it.isVideo }
          val rotation = _uiState.value.heroRotation + if (advanceHero) 1 else 0
          val heroItems = mixedHeroItems(videos, rotation)
          val fallbackTopPicks = heroItems.filterNot { candidate -> watchHistory.any { it.id == candidate.id } }
          _uiState.update { state ->
            state.copy(
              libraries = libs,
              heroItems = heroItems,
              heroRotation = rotation,
              watchHistory = watchHistory,
              topPicks = fallbackTopPicks,
              latestMovies = videos.filter { it.mediaType.equals("Movie", ignoreCase = true) }.take(20),
              latestShows = groupShows(videos.filterNot { it.isAnime }).take(20),
              latestAnime = videos.filter { it.isAnime }.take(20),
              latestMusic = audio.take(20),
              currentItems = allItems,
              isLoading = false,
            )
          }
          // Similar-items is optional enrichment. It must never delay or clear the usable dashboard.
          viewModelScope.launch(Dispatchers.IO) {
            val serverTopPicks = watchHistory.firstOrNull()?.let { historyItem ->
              jellyfin.loadSimilarItems(session, historyItem.id, limit = 20).getOrDefault(emptyList())
            }.orEmpty()
            if (serverTopPicks.isNotEmpty()) {
              _uiState.update { state -> state.copy(topPicks = serverTopPicks) }
            }
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
          itemTypes = libraryItemTypes(it.collectionType, it.name),
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
          includeItemTypes = libView?.itemTypes,
          libraryName = library?.name,
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
    if (libraryId != null) {
      loadLibrary(libraryId)
      return
    }

    // Swap the visible batch before the network request starts. This makes a
    // pull-to-refresh feel instant while the refreshed dashboard loads below it.
    val state = _uiState.value
    val nextRotation = state.heroRotation + 1
    val nextHeroItems = mixedHeroItems(state.currentItems.filter { it.isVideo }, nextRotation)
    _uiState.update { it.copy(heroRotation = nextRotation, heroItems = nextHeroItems) }
    viewModelScope.launch { loadHome(session) }
  }

  fun loadMore() {
    val session = _uiState.value.session ?: return
    val libraryId = _uiState.value.selectedLibraryId ?: return
    val libraryTypes = _uiState.value.openLibrary?.itemTypes
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
          includeItemTypes = libraryTypes,
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

  fun clearSearch() {
    searchJob?.cancel()
    searchGeneration += 1
    val libraryId = _uiState.value.selectedLibraryId
    _uiState.update { it.copy(searchQuery = "", currentItems = if (libraryId == null) it.currentItems else emptyList(), error = null) }
    if (libraryId != null) loadLibrary(libraryId) else {
      val session = _uiState.value.session ?: return
      viewModelScope.launch { loadHome(session) }
    }
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
      clearSearch()
      return
    }
    searchJob = viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, searchQuery = query, error = null) }
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
    val session = _uiState.value.session ?: return
    val client = httpClient ?: return
    if (tracks.isEmpty()) return
    val jellyfin = JellyfinClient(client, context)
    val playableTracks = tracks.filter { it.isPlayable || it.isVideo }
    if (playableTracks.isEmpty()) return
    val safeIndex = index.coerceIn(0, playableTracks.lastIndex)
    val track = playableTracks[safeIndex]
    val playlist = ArrayList<Uri>(playableTracks.size)
    val playlistTitles = ArrayList<String>(playableTracks.size)
    playableTracks.forEach { item ->
      playlist.add(Uri.parse(item.streamUrl ?: jellyfin.getStreamUrl(session, item.id, isVideo = item.isVideo)))
      val number = listOfNotNull(item.seasonNumber?.let { "S%02d".format(it) }, item.episodeNumber?.let { "E%02d".format(it) }).joinToString("")
      playlistTitles.add(if (number.isBlank()) item.title else "$number · ${item.title}")
    }
    val intent = Intent(Intent.ACTION_VIEW, playlist[safeIndex]).apply {
      setClass(context, Class.forName("app.infinity.mpvz.ui.player.PlayerActivity"))
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("internal_launch", true)
      putExtra("launch_source", "jellyfin")
      putExtra("is_audio", !track.isVideo)
      putExtra("title", track.title)
      putParcelableArrayListExtra("playlist", playlist)
      putExtra("playlist_index", safeIndex)
      putStringArrayListExtra("playlist_titles", playlistTitles)
      putExtra("jellyfin_server_url", session.serverUrl)
      putExtra("jellyfin_user_id", session.userId)
      putExtra("jellyfin_access_token", session.accessToken)
      putExtra("jellyfin_item_id", track.id)
      putExtra("jellyfin_season_number", track.seasonNumber ?: -1)
      putExtra("jellyfin_episode_number", track.episodeNumber ?: -1)
    }
    context.startActivity(intent)
  }

  fun openDetail(track: JellyfinTrack) {
    val session = _uiState.value.session ?: return
    val client = httpClient ?: return
    _uiState.update {
      it.copy(
        detailItem = track,
        detailSeasons = emptyList(),
        detailEpisodes = emptyList(),
        detailSimilarItems = emptyList(),
        isDetailLoading = true,
        isDetailEpisodesLoading = track.mediaType.equals("Series", ignoreCase = true),
      )
    }
    viewModelScope.launch {
      val jellyfin = JellyfinClient(client, getApplication())
      val fullItem = jellyfin.loadItem(session, track.id).getOrNull() ?: track
      val similarItems = jellyfin.loadSimilarItems(session, fullItem.id).getOrDefault(emptyList())
      _uiState.update { it.copy(detailItem = fullItem, detailSimilarItems = similarItems, isDetailLoading = false) }
      if (fullItem.mediaType.equals("Series", ignoreCase = true)) {
        jellyfin.loadMedia(session, fullItem.id, limit = 100, sortBy = "SortName", sortOrder = "Ascending").fold(
          onSuccess = { seasons ->
            val seasonItems = seasons.filter { it.mediaType.equals("Season", ignoreCase = true) }
            _uiState.update { it.copy(detailSeasons = seasonItems) }
            val episodes = mutableListOf<JellyfinTrack>()
            for (season in seasonItems) {
              jellyfin.loadMedia(session, season.id, limit = 200, sortBy = "ParentIndexNumber,IndexNumber", sortOrder = "Ascending")
                .onSuccess { episodes.addAll(it.filter { item -> item.mediaType.equals("Episode", ignoreCase = true) }) }
            }
            _uiState.update { it.copy(detailEpisodes = episodes, isDetailEpisodesLoading = false) }
          },
          onFailure = { _uiState.update { it.copy(isDetailEpisodesLoading = false) } },
        )
      } else {
        _uiState.update { it.copy(isDetailEpisodesLoading = false) }
      }
    }
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
