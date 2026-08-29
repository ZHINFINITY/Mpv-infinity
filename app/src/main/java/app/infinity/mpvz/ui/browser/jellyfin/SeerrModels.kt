package app.infinity.mpvz.ui.browser.jellyfin

data class SeerrMediaItem(
  val id: Int,
  val title: String,
  val mediaType: String,
  val posterPath: String? = null,
  val backdropPath: String? = null,
  val overview: String = "",
  val releaseDate: String? = null,
  val voteAverage: Double? = null,
  val requested: Boolean = false,
  val requested4k: Boolean = false,
  val isRequesting: Boolean = false,
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
)
