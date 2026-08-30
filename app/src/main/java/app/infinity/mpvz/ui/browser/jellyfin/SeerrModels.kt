package app.infinity.mpvz.ui.browser.jellyfin

data class SeerrCastMember(
  val name: String,
  val character: String? = null,
  val profilePath: String? = null,
)

data class SeerrSeason(
  val seasonNumber: Int,
  val name: String = "Season $seasonNumber",
  val episodeCount: Int = 0,
  val available: Boolean = false,
  val requested: Boolean = false,
)

enum class SeerrAudioPreference(val label: String) {
  DEFAULT("Default audio"),
  DUB("Dub"),
  SUB("Subtitles"),
}

data class SeerrMediaItem(
  val id: Int,
  val title: String,
  val mediaType: String,
  val posterPath: String? = null,
  val backdropPath: String? = null,
  val overview: String = "",
  val releaseDate: String? = null,
  val voteAverage: Double? = null,
  val genres: List<String> = emptyList(),
  val cast: List<SeerrCastMember> = emptyList(),
  val seasons: List<SeerrSeason> = emptyList(),
  val availableInJellyfin: Boolean = false,
  val partiallyAvailable: Boolean = false,
  val jellyfinMediaId: String? = null,
  val requested: Boolean = false,
  val requested4k: Boolean = false,
  val isRequesting: Boolean = false,
  val requestError: String? = null,
)

data class SeerrDiscoverState(
  val isConnected: Boolean = false,
  val isLoading: Boolean = false,
  val error: String? = null,
  val userName: String? = null,
  val movies: List<SeerrMediaItem> = emptyList(),
  val shows: List<SeerrMediaItem> = emptyList(),
  val trending: List<SeerrMediaItem> = emptyList(),
  val searchQuery: String = "",
  val searchResults: List<SeerrMediaItem> = emptyList(),
  val isSearching: Boolean = false,
  val detailLoadingKey: String? = null,
  val details: Map<String, SeerrMediaItem> = emptyMap(),
)
