package app.infinity.mpvz.catalog

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.decodeFromString
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
private const val DIAG_TAG = "MpvCatalogDiag"
private const val DEFAULT_STREAM_PATH = "/stream/{type}/{imdbId}.json"
private const val KITSU_CATALOG_URL = "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-popular.json"
private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io/catalog"
private val DEFAULT_CATALOG_SOURCES = listOf(
  CatalogSource("cinemeta-movies", "Cinemeta Movies", "https://v3-cinemeta.strem.io/manifest.json"),
  CatalogSource("cinemeta-series", "Cinemeta Series", "https://v3-cinemeta.strem.io/manifest.json"),
  CatalogSource("kitsu-anime", "Kitsu Anime", "https://anime-kitsu.strem.fun/manifest.json"),
)

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
  var autoChooseBestTorrent: Boolean
    get() = prefs.getBoolean("auto_choose_best_torrent", false)
    set(value) = prefs.edit().putBoolean("auto_choose_best_torrent", value).apply()
  fun resolvers(): List<ResolverEndpoint> = prefs.getStringSet("resolver_endpoints", emptySet()).orEmpty().mapNotNull { encoded ->
    val parts = encoded.split("|", limit = 2)
    parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { ResolverEndpoint(sanitizeResolverBaseUrl(it), parts.getOrNull(1)?.toBooleanStrictOrNull() ?: true) }
  }
  fun saveResolvers(value: List<ResolverEndpoint>) {
    prefs.edit().putStringSet("resolver_endpoints", value.mapNotNull { endpoint ->
      sanitizeResolverBaseUrl(endpoint.baseUrl).takeIf { it.isNotBlank() }?.let { "$it|${endpoint.enabled}" }
    }.toSet()).commit()
  }
  fun catalogSources(): List<CatalogSource> {
    prefs.getString("catalog_sources_json", null)?.let { encoded ->
      runCatching { Json.decodeFromString<List<CatalogSource>>(encoded) }.getOrNull()?.let { return it }
    }
    return prefs.getStringSet("catalog_sources", null)?.mapNotNull { encoded ->
      val parts = encoded.split("|", limit = 4)
      if (parts.size >= 4) CatalogSource(parts[0], parts[1], parts[2], parts[3].toBooleanStrictOrNull() ?: true) else null
    } ?: DEFAULT_CATALOG_SOURCES
  }
  fun saveCatalogSources(value: List<CatalogSource>) {
    prefs.edit().putString("catalog_sources_json", Json.encodeToString(value)).remove("catalog_sources").apply()
  }
}

private fun sanitizeResolverBaseUrl(value: String): String = value.trim().trimEnd('/')
  .removeSuffix("/manifest.json")
  .removeSuffix("/stream")
  .trimEnd('/')

class KitsuAnimeRepository {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun popular(query: String? = null): List<MediaItem> = withContext(Dispatchers.IO) {
    val url = KITSU_CATALOG_URL.removeSuffix(".json") + (query?.takeIf { it.isNotBlank() }?.let { "/search=${URLEncoder.encode(it, "UTF-8")}" } ?: "") + ".json"
    client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
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
          catalogSourceId = "kitsu-anime",
          releaseYear = meta["releaseInfo"]?.jsonPrimitive?.contentOrNull,
          contentRating = meta["imdbRating"]?.jsonPrimitive?.contentOrNull,
          duration = meta["runtime"]?.jsonPrimitive?.contentOrNull,
          genres = meta["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
      }
    }
  }
}

class StremioCatalogRepository {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun manifestName(url: String): String? = withContext(Dispatchers.IO) {
    runCatching { getJson(url).jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
  }

  suspend fun load(source: CatalogSource, query: String?): List<MediaItem> = withContext(Dispatchers.IO) {
    Log.i(DIAG_TAG, "catalog start source=${source.id} query=${query ?: "<home>"} manifest=${source.manifestUrl}")
    runCatching {
      val manifest = getJson(source.manifestUrl).jsonObject
      val catalogs = manifest["catalogs"]?.jsonArray.orEmpty()
      catalogs.flatMap { catalogElement ->
        runCatching {
          val catalog = catalogElement.jsonObject
          val type = catalog["type"]?.jsonPrimitive?.contentOrNull ?: return@runCatching emptyList()
          val id = catalog["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching emptyList()
          val extras = catalog["extra"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.lowercase() }
          val supportsSearch = "search" in extras
          if (!query.isNullOrBlank() && !supportsSearch) return@runCatching emptyList()
          val suffix = if (!query.isNullOrBlank()) "/search=${encodePathSegment(query)}" else ""
          val base = source.manifestUrl.trimEnd('/').removeSuffix("manifest.json")
          Log.i(DIAG_TAG, "catalog request source=${source.id} type=$type id=$id supportsSearch=$supportsSearch url=${base}catalog/$type/$id$suffix.json")
          val payload = getJson("${base}catalog/$type/$id$suffix.json").jsonObject
          payload["metas"]?.jsonArray.orEmpty().mapNotNull { element ->
          val meta = element.jsonObject
          val providerId = meta["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val metaType = meta["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: type
          MediaItem(
            id = providerId.hashCode(),
            type = if (metaType == "movie") MediaType.MOVIE else MediaType.TV,
            title = meta["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            overview = meta["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            posterUrl = meta["poster"]?.jsonPrimitive?.contentOrNull,
            backdropUrl = meta["background"]?.jsonPrimitive?.contentOrNull,
            imdbId = meta["imdb_id"]?.jsonPrimitive?.contentOrNull,
            provider = CatalogProvider.CINEMETA,
            providerId = providerId,
            catalogSourceId = source.id,
            catalogType = metaType,
            catalogId = id,
            catalogName = catalog["name"]?.jsonPrimitive?.contentOrNull ?: id,
            releaseYear = meta["releaseInfo"]?.jsonPrimitive?.contentOrNull,
            contentRating = meta["imdbRating"]?.jsonPrimitive?.contentOrNull,
            duration = meta["runtime"]?.jsonPrimitive?.contentOrNull,
            genres = meta["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
              ?: meta["genre"]?.jsonPrimitive?.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
              ?: emptyList(),
          )
          }.filter { query.isNullOrBlank() || supportsSearch || it.title.contains(query, ignoreCase = true) || it.overview.contains(query, ignoreCase = true) }
        }.onFailure { error ->
          if (error is CancellationException) throw error
          Log.e(DIAG_TAG, "catalog failed source=${source.id} message=${error.message}", error)
        }.getOrDefault(emptyList())
      }
    }.onSuccess { items -> Log.i(DIAG_TAG, "catalog complete source=${source.id} items=${items.size}") }
      .onFailure { error ->
        if (error is CancellationException) throw error
        Log.e(DIAG_TAG, "manifest failed source=${source.id} message=${error.message}", error)
      }
      .getOrDefault(emptyList())
  }

  private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

  private suspend fun getJson(url: String): JsonElement {
    var attempt = 0
    while (true) {
      delay(150)
      val result = client.newCall(Request.Builder().url(url).header("User-Agent", "MpvInfinity/1.0").get().build()).execute().use { response ->
        if (response.code == 429 && attempt < 3) null
        else if (!response.isSuccessful) error("Stremio catalog request failed (${response.code})")
        else json.parseToJsonElement(response.body.string())
      }
      if (result != null) return result
      val waitMs = 500L shl attempt
      Log.w(DIAG_TAG, "catalog rate limited url=$url retry=${attempt + 1} waitMs=$waitMs")
      delay(waitMs)
      attempt++
    }
  }
}

class CinemetaCatalogRepository {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }
  suspend fun popular(): List<MediaItem> = request(null)
  suspend fun search(value: String): List<MediaItem> = request(value)

  suspend fun seasons(providerId: String): List<Season> = withContext(Dispatchers.IO) {
    val request = Request.Builder().url("https://v3-cinemeta.strem.io/meta/series/${URLEncoder.encode(providerId, "UTF-8")}.json").get().build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return@withContext emptyList()
      val videos = json.parseToJsonElement(response.body.string()).jsonObject["meta"]?.jsonObject?.get("videos")?.jsonArray.orEmpty()
      videos.mapNotNull { element ->
        val video = element.jsonObject
        val season = video["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val episode = video["episode"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        season to Episode(episode, video["title"]?.jsonPrimitive?.contentOrNull ?: "Episode $episode", video["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(), video["thumbnail"]?.jsonPrimitive?.contentOrNull, video["runtime"]?.jsonPrimitive?.contentOrNull)
      }.groupBy({ it.first }, { it.second }).map { (number, episodes) -> Season(number, episodes.sortedBy { it.number }) }.sortedBy { it.number }
    }
  }

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
          val seasons = meta["videos"]?.jsonArray.orEmpty().mapNotNull { videoElement ->
            val video = videoElement.jsonObject
            val season = video["season"]?.jsonPrimitive?.intOrNull
            val episode = video["episode"]?.jsonPrimitive?.intOrNull
            if (season == null || episode == null) null else Season(season, listOf(Episode(
              number = episode,
              title = video["title"]?.jsonPrimitive?.contentOrNull ?: "Episode $episode",
              overview = video["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
              stillUrl = video["thumbnail"]?.jsonPrimitive?.contentOrNull,
              runtime = video["runtime"]?.jsonPrimitive?.contentOrNull,
            )))
          }.groupBy { it.number }.map { (number, grouped) -> Season(number, grouped.flatMap { it.episodes }.sortedBy { it.number }) }.sortedBy { it.number }
          MediaItem(
            id = providerId.hashCode(),
            type = if (type == "series") MediaType.TV else MediaType.MOVIE,
            title = meta["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            overview = meta["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            posterUrl = meta["poster"]?.jsonPrimitive?.contentOrNull,
            backdropUrl = meta["background"]?.jsonPrimitive?.contentOrNull,
            provider = CatalogProvider.CINEMETA,
            providerId = providerId,
            catalogSourceId = if (type == "series") "cinemeta-series" else "cinemeta-movies",
            seasons = seasons,
            genres = meta["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
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

  override suspend fun resolve(item: MediaItem, season: Int?, episode: Int?): List<StreamOption> {
    return withContext(Dispatchers.IO) {
    val endpoints = settings.resolvers().filter { it.enabled }.ifEmpty {
      listOfNotNull(settings.resolvers().firstOrNull(), null).filter { it.enabled }
    }
    if (endpoints.isEmpty()) {
      Log.w("CloudStreamResolver", "No active stream resolver is configured")
      return@withContext emptyList()
    }
    Log.i(DIAG_TAG, "resolve start title=\"${item.title}\" type=${item.catalogType ?: item.type} providerId=${item.providerId ?: "<none>"} endpoints=${endpoints.map { it.baseUrl }} season=$season episode=$episode")
    coroutineScope {
      endpoints.map { endpoint ->
        async {
          val types = when {
            item.provider == CatalogProvider.KITSU -> listOf("anime", "series", "movie")
            !item.catalogType.isNullOrBlank() -> listOf(item.catalogType)
            else -> listOf(null)
          }
          val identifiers = if (item.provider == CatalogProvider.KITSU) {
            listOfNotNull(item.providerId, item.imdbId, item.id.toString()).distinct()
          } else listOf(null)
          types.flatMap { type ->
            identifiers.map { identifier ->
              runCatching { resolveFromEndpoint(endpoint.baseUrl, item, season, episode, type, identifier) }
                .onFailure { error -> Log.w("CloudStreamResolver", "Resolver ${endpoint.baseUrl} failed: ${error.message}") }
                .getOrDefault(emptyList())
            }.flatten()
          }
        }
      }.awaitAll().flatten()
        .distinctBy { it.url }
        .sortedWith(compareByDescending<StreamOption> { it.isPlayable }.thenByDescending { it.qualityRank }.thenByDescending { it.seeders })
        .also { Log.i(DIAG_TAG, "resolve complete title=\"${item.title}\" streams=${it.size} playable=${it.count { stream -> stream.isPlayable }}") }
    }
    }
  }

  private suspend fun resolveFromEndpoint(
    baseUrl: String,
    item: MediaItem,
    season: Int?,
    episode: Int?,
    typeOverride: String? = null,
    identifierOverride: String? = null,
  ): List<StreamOption> {
    val identifier = identifierOverride ?: item.providerId?.takeIf { it.isNotBlank() } ?: item.imdbId?.takeIf { it.isNotBlank() } ?: item.id.toString()
    val type = typeOverride ?: item.catalogType ?: when {
      item.provider == CatalogProvider.KITSU -> "anime"
      item.type == MediaType.TV -> "series"
      else -> "movie"
    }
    val configuredPath = settings.resolverPath
    val resourceIdentifier = if (item.type == MediaType.TV && season != null) {
      if (episode != null) "$identifier:$season:$episode" else "$identifier:$season"
    } else identifier
    val path = if (configuredPath == DEFAULT_STREAM_PATH && resourceIdentifier != identifier) {
      "/stream/$type/$resourceIdentifier.json"
    } else configuredPath
      .replace("{type}", type)
      .replace("{imdbId}", resourceIdentifier)
      .replace("{tmdbId}", item.id.toString())
      .replace("{season}", season?.toString().orEmpty())
      .replace("{episode}", episode?.toString().orEmpty())
      .let { if (it.startsWith("/")) it else "/$it" }
    val request = Request.Builder()
      .url(baseUrl.trimEnd('/').removeSuffix("/manifest.json") + path)
      .header("User-Agent", "Mozilla/5.0 (Android) mpv-infinity/1.0")
      .header("Accept", "application/json")
      .apply { if (settings.resolverToken.isNotBlank()) addHeader("Authorization", "Bearer ${settings.resolverToken}") }
      .get()
      .build()
    Log.i(DIAG_TAG, "resolver request endpoint=${baseUrl.trimEnd('/')} path=$path type=$type identifier=$identifier season=$season episode=$episode")
    return client.newCall(request).execute().use { response ->
      Log.i(DIAG_TAG, "resolver response endpoint=${baseUrl.trimEnd('/')} path=$path status=${response.code}")
      if (!response.isSuccessful) {
        Log.w("CloudStreamResolver", "Resolver ${request.url} returned HTTP ${response.code}")
        error("Resolver request failed (${response.code})")
      }
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
            audioCodec = element["audioCodec"]?.jsonPrimitive?.contentOrNull,
            videoCodec = element["videoCodec"]?.jsonPrimitive?.contentOrNull,
            torrentFileIndex = element["fileIdx"]?.jsonPrimitive?.intOrNull,
            season = element["season"]?.jsonPrimitive?.intOrNull,
            episode = element["episode"]?.jsonPrimitive?.intOrNull,
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
            audioCodec = element["audioCodec"]?.jsonPrimitive?.contentOrNull,
            videoCodec = element["videoCodec"]?.jsonPrimitive?.contentOrNull,
            torrentFileIndex = element["fileIdx"]?.jsonPrimitive?.intOrNull,
            season = element["season"]?.jsonPrimitive?.intOrNull,
            episode = element["episode"]?.jsonPrimitive?.intOrNull,
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
