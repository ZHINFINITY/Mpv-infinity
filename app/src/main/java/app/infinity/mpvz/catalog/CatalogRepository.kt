package app.infinity.mpvz.catalog

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

private interface TmdbApi {
  @GET("trending/all/week")
  suspend fun trending(@Query("api_key") apiKey: String): TmdbPage
  @GET("search/multi")
  suspend fun search(@Query("api_key") apiKey: String, @Query("query") query: String): TmdbPage
  @GET("{type}/{id}")
  suspend fun details(@Path("type") type: String, @Path("id") id: Int, @Query("api_key") apiKey: String): TmdbDetails
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
  suspend fun resolve(item: MediaItem): ResolverResponse
}

/**
 * Adapter for a user-controlled debrid/cloud resolver. The app deliberately does not scrape
 * public torrent indexes or embed provider credentials; deployments provide this HTTPS endpoint.
 * The endpoint receives stable TMDB metadata and returns a short-lived direct media URL.
 */
class CloudStreamResolver(private val settings: CatalogSettings) : StreamResolver {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  override suspend fun resolve(item: MediaItem): ResolverResponse = withContext(Dispatchers.IO) {
    val baseUrl = settings.resolverBaseUrl.ifBlank { error("Configure a resolver endpoint in Catalog settings first.") }
    val request = Request.Builder()
      .url("$baseUrl/resolve")
      .addHeader("Authorization", "Bearer ${settings.resolverToken}")
      .post(
        json.encodeToString(ResolverRequest.serializer(), ResolverRequest(item.id, item.imdbId, item.title, item.type))
          .toRequestBody("application/json".toMediaType()),
      )
      .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("Resolver request failed (${response.code})")
      json.decodeFromString<ResolverResponse>(response.body.string()).also {
        require(it.url.startsWith("https://") || it.url.startsWith("http://")) { "Resolver returned an invalid media URL." }
      }
    }
  }
}
