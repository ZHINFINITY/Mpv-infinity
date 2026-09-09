package app.infinity.mpvz.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MediaType { MOVIE, TV }
enum class CatalogProvider { CINEMETA, KITSU }

@Serializable
data class CatalogSource(
  val id: String,
  val name: String,
  val manifestUrl: String,
  val isEnabled: Boolean = true,
)

data class MediaItem(
  val id: Int,
  val type: MediaType,
  val title: String,
  val overview: String,
  val posterUrl: String?,
  val backdropUrl: String?,
  val imdbId: String? = null,
  val seasons: List<Season> = emptyList(),
  val provider: CatalogProvider = CatalogProvider.CINEMETA,
  val providerId: String? = null,
  val catalogSourceId: String? = null,
  val releaseYear: String? = null,
  val contentRating: String? = null,
  val duration: String? = null,
  val genres: List<String> = emptyList(),
)

@Serializable
data class Season(val number: Int, val episodes: List<Episode> = emptyList())
@Serializable
data class Episode(val number: Int, val title: String, val overview: String, val stillUrl: String?, val runtime: String? = null)

data class CatalogState(
  val query: String = "",
  val items: List<MediaItem> = emptyList(),
  val isLoading: Boolean = false,
  val isLoadingMore: Boolean = false,
  val catalogPage: Int = 1,
  val canLoadMore: Boolean = true,
  val resolvingId: Int? = null,
  val error: String? = null,
  val streamOptions: List<StreamOption> = emptyList(),
  val streamTitle: String? = null,
  val selectedItem: MediaItem? = null,
  val selectedSeason: Int? = null,
  val selectedEpisode: Int? = null,
  val sourceFilter: String = "All",
  val sourceSort: String = "Best",
  val enabledProviders: Set<CatalogProvider> = CatalogProvider.entries.toSet(),
)

@Serializable
data class StreamOption(
  val url: String,
  val title: String,
  val mimeType: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val qualityRank: Int = 0,
  val seeders: Int = 0,
  val size: String? = null,
  val source: String? = null,
  val audioCodec: String? = null,
  val videoCodec: String? = null,
  val isPlayable: Boolean = url.startsWith("http://") || url.startsWith("https://"),
  val torrentFileIndex: Int? = null,
  val season: Int? = null,
  val episode: Int? = null,
)

@Serializable
data class ResolverRequest(
  val tmdbId: Int,
  val imdbId: String? = null,
  val title: String,
  val type: MediaType,
)

@Serializable
data class ResolverResponse(
  val url: String,
  val mimeType: String? = null,
  val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class StremioStreamResponse(val streams: List<StremioStream> = emptyList())

@Serializable
data class StremioStream(
  val url: String? = null,
  val externalUrl: String? = null,
  val behaviorHints: Map<String, String> = emptyMap(),
)
