package app.infinity.mpvz.catalog

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.contentOrNull
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
import java.net.URLEncoder

private const val PREFS = "catalog_secure_settings"
private const val DEFAULT_STREAM_PATH = "/stream/{type}/{imdbId}.json"
private const val KITSU_CATALOG_URL = "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-popular.json"
private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io/catalog"

class CatalogSettings(context: Context) {
  private val prefs = EncryptedSharedPreferences.create(
    context,
    PREFS,
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
  )
  var resolverToken: String
    get() = prefs.getString("resolver_token", "") ?: ""
    set(value) = prefs.edit().putString("resolver_token", value.trim()).apply()
  var resolverPath: String
    get() = prefs.getString("resolver_path", DEFAULT_STREAM_PATH) ?: DEFAULT_STREAM_PATH
    set(value) = prefs.edit().putString("resolver_path", value.trim().ifBlank { DEFAULT_STREAM_PATH }).apply()
  fun resolvers(): List<ResolverEndpoint> = prefs.getStringSet("resolver_endpoints", emptySet()).orEmpty().mapNotNull { encoded ->
    val parts = encoded.split("|", limit = 2)
    parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { ResolverEndpoint(it, parts.getOrNull(1)?.toBooleanStrictOrNull() ?: true) }
  }
  fun saveResolvers(value: List<ResolverEndpoint>) { prefs.edit().putStringSet("resolver_endpoints", value.map { "${it.baseUrl}|${it.enabled}" }.toSet()).apply() }
}

class KitsuAnimeRepository {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun popular(): List<MediaItem> = withContext(Dispatchers.IO) {
    client.newCall(Request.Builder().url(KITSU_CATALOG_URL).get().build()).execute().use { response ->
      if (!response.isSuccessful) error("Kitsu catalog request failed (${response.code})")
      val metas = json.parseToJsonElement(response.body.string()).jsonObject["metas"]?.jsonArray.orEmpty()
      metas.mapNotNull { entry ->
        val meta = entry.jsonObject
        val id = meta["kitsu_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        MediaItem(
          id = -id.hashCode(), provider = CatalogProvider.KITSU, providerId = id,
          type = if (meta["type"]?.jsonPrimitive?.contentOrNull == "movie") MediaType.MOVIE else MediaType.TV,
          title = meta["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
          overview = meta["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
          posterUrl = meta["poster"]?.jsonPrimitive?.contentOrNull,
          backdropUrl = meta["background"]?.jsonPrimitive?.contentOrNull,
          imdbId = meta["imdb_id"]?.jsonPrimitive?.contentOrNull,
          releaseYear = meta["releaseInfo"]?.jsonPrimitive?.contentOrNull,
          contentRating = meta["imdbRating"]?.jsonPrimitive?.contentOrNull,
          duration = meta["runtime"]?.jsonPrimitive?.contentOrNull,
          genres = meta["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
      }
    }
  }
}

class CinemetaCatalogRepository {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun popular(): List<MediaItem> = request(null)
  suspend fun search(value: String): List<MediaItem> = request(value)

  private suspend fun request(value: String?): List<MediaItem> = withContext(Dispatchers.IO) {
    listOf("movie", "series").flatMap { type ->
      val suffix = value?.let { "/search=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
      val request = Request.Builder().url("$CINEMETA_BASE_URL/$type/top$suffix.json").get().build()
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@flatMap emptyList()
        val metas = json.parseToJsonElement(response.body.string()).jsonObject["metas"]?.jsonArray.orEmpty()
        metas.mapNotNull { entry ->
          val meta = entry.jsonObject
          val providerId = meta["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          MediaItem(
            id = providerId.hashCode(),
            type = if (type == "series") MediaType.TV else MediaType.MOVIE,
            title = meta["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            overview = meta["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            posterUrl = meta["poster"]?.jsonPrimitive?.contentOrNull,
            backdropUrl = meta["background"]?.jsonPrimitive?.contentOrNull,
            provider = CatalogProvider.CINEMETA,
            providerId = providerId,
          )
        }
      }
    }
  }
}

data class ResolverEndpoint(val baseUrl: String, val enabled: Boolean = true)

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
    val endpoints = settings.resolvers().filter { it.enabled }.ifEmpty {
      listOfNotNull(settings.resolvers().firstOrNull(), null).filter { it.enabled }
    }
    require(endpoints.isNotEmpty()) { "Add an active stream resolver in Stream settings first." }
    return coroutineScope {
      endpoints.map { endpoint -> async { resolveFromEndpoint(endpoint.baseUrl, item, season, episode) } }.awaitAll().flatten()
        .distinctBy { it.url }
        .sortedWith(compareByDescending<StreamOption> { it.isPlayable }.thenByDescending { it.qualityRank }.thenByDescending { it.seeders })
    }
  }

  private suspend fun resolveFromEndpoint(baseUrl: String, item: MediaItem, season: Int?, episode: Int?): List<StreamOption> {
    val identifier = item.providerId?.takeIf { it.isNotBlank() } ?: item.imdbId?.takeIf { it.isNotBlank() } ?: item.id.toString()
    val type = if (item.type == MediaType.TV) "series" else "movie"
    val configuredPath = settings.resolverPath
    val resourceIdentifier = if (type == "series" && season != null && episode != null) "$identifier:$season:$episode" else identifier
    val path = if (configuredPath == DEFAULT_STREAM_PATH && resourceIdentifier != identifier) {
      "/stream/series/$resourceIdentifier.json"
    } else configuredPath
      .replace("{type}", type)
      .replace("{imdbId}", resourceIdentifier)
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
      val parsed = parseStreams(json.parseToJsonElement(response.body.string()), depth = 0)
      require(parsed.isNotEmpty()) {
        "Resolver returned no streams. Expected a streams array with url, magnet, or infoHash entries."
      }
      parsed
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
      (element["url"] ?: element["externalUrl"] ?: element["stream"] ?: element["magnet"])
        ?.jsonPrimitive?.content?.let { url ->
          val title = element["title"]?.jsonPrimitive?.content ?: element["name"]?.jsonPrimitive?.content ?: "Stream"
          StreamOption(
            url = url,
            title = title,
            qualityRank = qualityRank("$title $url"),
            seeders = element["seeders"]?.jsonPrimitive?.intOrNull ?: element["peers"]?.jsonPrimitive?.intOrNull ?: 0,
            size = element["size"]?.jsonPrimitive?.content,
            source = element["source"]?.jsonPrimitive?.content,
            torrentFileIndex = element["fileIdx"]?.jsonPrimitive?.intOrNull,
          )
        }
        ?: element["infoHash"]?.jsonPrimitive?.content?.let { hash ->
          val title = element["title"]?.jsonPrimitive?.content ?: element["name"]?.jsonPrimitive?.content ?: "Torrent"
          StreamOption(
            url = "magnet:?xt=urn:btih:${hash.trim()}",
            title = title,
            qualityRank = qualityRank("$title ${element["title"]?.jsonPrimitive?.content.orEmpty()}"),
            seeders = element["seeders"]?.jsonPrimitive?.intOrNull ?: element["peers"]?.jsonPrimitive?.intOrNull ?: 0,
            size = element["size"]?.jsonPrimitive?.content,
            source = element["source"]?.jsonPrimitive?.content,
            torrentFileIndex = element["fileIdx"]?.jsonPrimitive?.intOrNull,
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
