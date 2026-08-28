/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.net.Uri

data class JellyfinSession(
  val serverUrl: String,
  val userId: String,
  val accessToken: String,
)

data class JellyfinQuickConnectState(
  val serverUrl: String,
  val secret: String,
  val code: String,
)

data class JellyfinCollection(
  val id: String,
  val name: String,
  val collectionType: String?,
)

data class JellyfinTrack(
  val id: String,
  val title: String,
  val artist: String,
  val album: String,
  val durationMs: Long,
  val artworkUrl: String?,
  val streamUrl: String?,
  val mediaType: String = "Audio",
  val overview: String = "",
  val productionYear: Int? = null,
  val communityRating: Double? = null,
  val genres: List<String> = emptyList(),
  val qualityLabel: String? = null,
  val trailerUrl: String? = null,
  val studio: String? = null,
) {
  val isVideo: Boolean
    get() = mediaType.equals("Movie", ignoreCase = true) ||
      mediaType.equals("Episode", ignoreCase = true) ||
      mediaType.equals("Series", ignoreCase = true) ||
      mediaType.equals("Season", ignoreCase = true)

  val isPlayable: Boolean
    get() = !streamUrl.isNullOrBlank()

  val uri: Uri?
    get() = streamUrl?.let(Uri::parse)
}

enum class JellyfinSortBy(val apiValue: String, val displayName: String) {
  NAME("SortName", "Title"),
  DATE_ADDED("DateCreated", "Recently Added"),
  DATE_PLAYED("DatePlayed", "Recently Played"),
  PREMIERE_DATE("PremiereDate", "Release Date"),
  RATING("CommunityRating", "Rating"),
  RUNTIME("Runtime", "Duration"),
  RANDOM("Random", "Random"),
}

enum class JellyfinSortOrder(val apiValue: String, val displayName: String) {
  ASCENDING("Ascending", "Ascending"),
  DESCENDING("Descending", "Descending"),
}

enum class JellyfinSearchCategory(val displayName: String) {
  ALL("All"),
  MOVIES("Movies"),
  SHOWS("TV Shows"),
  EPISODES("Episodes"),
}

enum class JellyfinMusicTab(val title: String) {
  HOME("Home"),
  TRACKS("Songs"),
  ALBUMS("Albums"),
  ARTISTS("Artists"),
  PLAYLISTS("Playlists"),
}

data class JellyfinLibraryView(
  val id: String,
  val title: String,
  val itemTypes: String,
  val collectionType: String? = null,
  val isMusic: Boolean = false,
)

data class JellyfinUiState(
  val session: JellyfinSession? = null,
  val libraries: List<JellyfinCollection> = emptyList(),
  val heroItems: List<JellyfinTrack> = emptyList(),
  val resumeItems: List<JellyfinTrack> = emptyList(),
  val latestMovies: List<JellyfinTrack> = emptyList(),
  val latestShows: List<JellyfinTrack> = emptyList(),
  val latestMusic: List<JellyfinTrack> = emptyList(),
  val currentItems: List<JellyfinTrack> = emptyList(),
  val openLibrary: JellyfinLibraryView? = null,
  val selectedLibraryId: String? = null,
  val sortBy: JellyfinSortBy = JellyfinSortBy.NAME,
  val sortOrder: JellyfinSortOrder = JellyfinSortOrder.ASCENDING,
  val totalRecordCount: Int = 0,
  val isLoading: Boolean = false,
  val isLoadingMore: Boolean = false,
  val hasMore: Boolean = false,
  val error: String? = null,
  val searchQuery: String = "",
  val searchCategory: JellyfinSearchCategory = JellyfinSearchCategory.ALL,
  val musicActiveTab: JellyfinMusicTab = JellyfinMusicTab.HOME,
  val musicFavorites: List<JellyfinTrack> = emptyList(),
  val musicRecentlyPlayed: List<JellyfinTrack> = emptyList(),
  val musicAlbums: List<JellyfinTrack> = emptyList(),
  val musicArtists: List<JellyfinTrack> = emptyList(),
  val musicTracks: List<JellyfinTrack> = emptyList(),
  val isMusicLoading: Boolean = false,
  val detailItem: JellyfinTrack? = null,
  val detailSeasons: List<JellyfinTrack> = emptyList(),
  val detailEpisodes: List<JellyfinTrack> = emptyList(),
  val detailSimilarItems: List<JellyfinTrack> = emptyList(),
  val isDetailLoading: Boolean = false,
  val isDetailEpisodesLoading: Boolean = false,
  val servers: List<JellyfinServerProfile> = emptyList(),
  val activeServer: JellyfinServerProfile? = null,
  val isAuthenticating: Boolean = false,
  val authError: String? = null,
)

data class JellyfinServerProfile(
  val id: String,
  val name: String,
  val serverUrl: String,
  val userId: String,
  val username: String,
  val accessToken: String,
)
