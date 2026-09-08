package app.infinity.mpvz.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MediaType { MOVIE, TV }
enum class CatalogProvider { TMDB, MYANIMELIST }

data class MediaItem(
  val id: Int,
  val type: MediaType,
  val title: String,
  val overview: String,
  val posterUrl: String?,
  val backdropUrl: String?,
  val imdbId: String? = null,
  val seasons: List<Season> = emptyList(),
  val provider: CatalogProvider = CatalogProvider.TMDB,
  val providerId: String? = null,
)

data class Season(val number: Int, val episodes: List<Episode> = emptyList())
data class Episode(val number: Int, val title: String, val overview: String, val stillUrl: String?)

data class CatalogState(
  val query: String = "",
  val items: List<MediaItem> = emptyList(),
  val isLoading: Boolean = false,
  val resolvingId: Int? = null,
  val error: String? = null,
  val streamOptions: List<StreamOption> = emptyList(),
  val streamTitle: String? = null,
  val selectedItem: MediaItem? = null,
  val selectedSeason: Int? = null,
  val selectedEpisode: Int? = null,
  val sourceFilter: String = "All",
  val enabledProviders: Set<CatalogProvider> = setOf(CatalogProvider.TMDB, CatalogProvider.MYANIMELIST),
)

data class StreamOption(
  val url: String,
  val title: String,
  val mimeType: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val qualityRank: Int = 0,
  val seeders: Int = 0,
  val size: String? = null,
  val source: String? = null,
  val isPlayable: Boolean = url.startsWith("http://") || url.startsWith("https://"),
  val torrentFileIndex: Int? = null,
)

@Serializable
data class TmdbPage(val results: List<TmdbResult> = emptyList())

@Serializable
data class TmdbResult(
  val id: Int,
  val title: String? = null,
  val name: String? = null,
  val overview: String? = null,
  @SerialName("poster_path") val posterPath: String? = null,
  @SerialName("backdrop_path") val backdropPath: String? = null,
  @SerialName("media_type") val mediaType: String? = null,
  @SerialName("first_air_date") val firstAirDate: String? = null,
  @SerialName("release_date") val releaseDate: String? = null,
)

@Serializable
data class TmdbDetails(
  val id: Int,
  val name: String? = null,
  val title: String? = null,
  val overview: String? = null,
  @SerialName("poster_path") val posterPath: String? = null,
  @SerialName("backdrop_path") val backdropPath: String? = null,
  val seasons: List<TmdbSeason> = emptyList(),
  @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
)

@Serializable
data class TmdbExternalIds(val imdb_id: String? = null)

@Serializable
data class JikanPage(val data: List<JikanAnime> = emptyList())

@Serializable
data class JikanAnime(
  val mal_id: Int,
  val title: String = "",
  val synopsis: String? = null,
  val type: String? = null,
  val images: JikanImages? = null,
)

@Serializable
data class JikanImages(val jpg: JikanImage? = null)

@Serializable
data class JikanImage(val image_url: String? = null, val large_image_url: String? = null)

@Serializable
data class TmdbSeason(
  val season_number: Int,
  val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
data class TmdbEpisode(
  val episode_number: Int,
  val name: String = "",
  val overview: String? = null,
  @SerialName("still_path") val stillPath: String? = null,
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
