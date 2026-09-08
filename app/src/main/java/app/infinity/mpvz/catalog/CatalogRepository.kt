package app.infinity.mpvz.catalog

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
private const val PREFS = "catalog_secure_settings"
private const val DEFAULT_STREAM_PATH = "/stream/{type}/{imdbId}.json"

private interface TmdbApi {
  @GET("trending/all/week")
  suspend fun trending(@Query("api_key") apiKey: String): TmdbPage
  @GET("search/multi")
  suspend fun search(@Query("api_key") apiKey: String, @Query("query") query: String): TmdbPage
  @GET("{type}/{id}")
  suspend fun details(@Path("type") type: String, @Path("id") id: Int, @Query("api_key") apiKey: String, @Query("append_to_response") append: String = "external_ids"): TmdbDetails
}

class CatalogSettings(context: Context) {
  private val prefs = EncryptedSharedPreferences.create(
    context,
    PREFS,
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
  )
  var tmdbApiKey: String
    get() = prefs.getString("tmdb_api_key", "") ?: ""
    set(value) = prefs.edit().putString("tmdb_api_key", value.trim()).apply()
  var resolverBaseUrl: String
    get() = prefs.getString("resolver_base_url", "") ?: ""
    set(value) = prefs.edit().putString("resolver_base_url", value.trim().trimEnd('/')).apply()
  var resolverToken: String
    get() = prefs.getString("resolver_token", "") ?: ""
    set(value) = prefs.edit().putString("resolver_token", value.trim()).apply()
  var resolverPath: String
    get() = prefs.getString("resolver_path", DEFAULT_STREAM_PATH) ?: DEFAULT_STREAM_PATH
    set(value) = prefs.edit().putString("resolver_path", value.trim().ifBlank { DEFAULT_STREAM_PATH }).apply()
}

class TmdbCatalogRepository(private val settings: CatalogSettings) {
  private val json = Json { ignoreUnknownKeys = true }
  private val api = Retrofit.Builder()
    .baseUrl("$TMDB_BASE_URL/")
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()
    .create(TmdbApi::class.java)

  suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
    require(settings.tmdbApiKey.isNotBlank()) { "Add a TMDB API key in Catalog settings first." }
    api.search(settings.tmdbApiKey, query).results
        .filter { it.mediaType == "movie" || it.mediaType == "tv" }
        .map { it.toMediaItem() }
  }

  suspend fun trending(): List<MediaItem> = withContext(Dispatchers.IO) {
    require(settings.tmdbApiKey.isNotBlank()) { "Add a TMDB API key in Catalog settings first." }
    api.trending(settings.tmdbApiKey).results
        .filter { it.mediaType == "movie" || it.mediaType == "tv" }
        .map { it.toMediaItem() }
  }

  suspend fun details(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
    require(settings.tmdbApiKey.isNotBlank()) { "Add a TMDB API key in Catalog settings first." }
    val type = if (item.type == MediaType.TV) "tv" else "movie"
    api.details(type, item.id, settings.tmdbApiKey).let { details ->
      item.copy(
        title = details.title ?: details.name ?: item.title,
        overview = details.overview ?: item.overview,
        posterUrl = details.posterPath?.let { IMAGE_BASE_URL + it } ?: item.posterUrl,
        backdropUrl = details.backdropPath?.let { IMAGE_BASE_URL + it } ?: item.backdropUrl,
        imdbId = details.externalIds?.imdb_id ?: item.imdbId,
        seasons = details.seasons.map { season ->
          Season(season.season_number, season.episodes.map { episode ->
            Episode(episode.episode_number, episode.name, episode.overview.orEmpty(), episode.stillPath?.let { IMAGE_BASE_URL + it })
          })
        },
      )
    }
  }

  private fun TmdbResult.toMediaItem() = MediaItem(
    id = id,
    type = if (mediaType == "tv" || name != null) MediaType.TV else MediaType.MOVIE,
    title = title ?: name.orEmpty(),
    overview = overview.orEmpty(),
    posterUrl = posterPath?.let { IMAGE_BASE_URL + it },
    backdropUrl = backdropPath?.let { IMAGE_BASE_URL + it },
  )
}

interface StreamResolver {
  suspend fun resolve(item: MediaItem, season: Int? = null, episode: Int? = null): List<StreamOption>
}

/**
 * Adapter for a user-controlled debrid/cloud resolver. The app deliberately does not scrape
 * public torrent indexes or embed provider credentials; deployments provide this HTTPS endpoint.
 * The endpoint receives stable TMDB metadata and returns a short-lived direct media URL.
 */
class CloudStreamResolver(private val settings: CatalogSettings) : StreamResolver {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun resolve(item: MediaItem, season: Int?, episode: Int?): List<StreamOption> = withContext(Dispatchers.IO) {
    val baseUrl = settings.resolverBaseUrl.ifBlank { error("Configure a resolver base URL in Catalog settings first.") }
    val identifier = item.imdbId?.takeIf { it.isNotBlank() } ?: item.id.toString()
    val type = if (item.type == MediaType.TV) "series" else "movie"
    val path = settings.resolverPath
      .replace("{type}", type)
      .replace("{imdbId}", identifier)
      .replace("{tmdbId}", item.id.toString())
      .replace("{season}", season?.toString().orEmpty())
      .replace("{episode}", episode?.toString().orEmpty())
      .let { if (it.startsWith("/")) it else "/$it" }
    val request = Request.Builder()
      .url(baseUrl.trimEnd('/') + path)
      .apply { if (settings.resolverToken.isNotBlank()) addHeader("Authorization", "Bearer ${settings.resolverToken}") }
      .get()
      .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("Resolver request failed (${response.code})")
      parseStreams(json.parseToJsonElement(response.body.string()), depth = 0)
    }
  }

  private fun parseStreams(element: JsonElement, depth: Int): List<StreamOption> {
    if (element is JsonObject) {
      element["streams"]?.let { return parseStreams(it, depth) }
    }
    val candidates = when (element) {
      is JsonArray -> element.flatMap { parseCandidate(it) }
      is JsonObject -> parseCandidate(element)
      else -> parseCandidate(element)
    }
    return candidates.mapNotNull { candidate ->
      val clean = sanitizeUrl(candidate.url)
      when {
        clean.startsWith("stremio://") -> resolveStremioResource(clean, candidate.title, depth)
        clean.isNotBlank() -> candidate.copy(url = clean, isPlayable = clean.startsWith("http://") || clean.startsWith("https://"))
        else -> null
      }
    }.sortedWith(compareByDescending<StreamOption> { it.isPlayable }.thenByDescending { it.qualityRank }.thenByDescending { it.seeders })
  }

  private fun parseCandidate(element: JsonElement): List<StreamOption> = when (element) {
    is JsonPrimitive -> listOf(StreamOption(element.content, "Stream", qualityRank = qualityRank(element.content)))
    is JsonObject -> listOfNotNull(
      (element["url"] ?: element["externalUrl"] ?: element["stream"])
        ?.jsonPrimitive?.content?.let { url ->
          val title = element["title"]?.jsonPrimitive?.content ?: element["name"]?.jsonPrimitive?.content ?: "Stream"
          StreamOption(
            url = url,
            title = title,
            qualityRank = qualityRank("$title $url"),
            seeders = element["seeders"]?.jsonPrimitive?.intOrNull ?: element["peers"]?.jsonPrimitive?.intOrNull ?: 0,
            size = element["size"]?.jsonPrimitive?.content,
            source = element["source"]?.jsonPrimitive?.content,
          )
        },
    )
    else -> emptyList()
  }

  private fun qualityRank(value: String): Int {
    val normalized = value.lowercase()
    return when {
      "2160p" in normalized || "4k" in normalized -> 2160
      "1440p" in normalized -> 1440
      "1080p" in normalized -> 1080
      "720p" in normalized -> 720
      "480p" in normalized -> 480
      else -> 0
    }
  }

  private fun sanitizeUrl(value: String): String = value.trim().removeSurrounding("[").removeSurrounding("]").trim('"', '\'', ' ', '\n', '\r', '\t')

  private fun resolveStremioResource(url: String, title: String, depth: Int): StreamOption? {
    require(depth < 2) { "Stremio resolver returned too many nested resources." }
    val resourceUrl = url.replaceFirst("stremio://", "https://")
    val request = Request.Builder().url(resourceUrl).build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("Stremio resource request failed (${response.code})")
      parseStreams(json.parseToJsonElement(response.body.string()), depth + 1).firstOrNull()?.copy(title = title)
    }
  }
}
