package app.infinity.mpvz.catalog

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val PREFS = "catalog_secure_settings"
private const val BUILTIN_CATALOG_REMOVED_PREF = "builtin_cinemeta_removed"
private const val DIAG_TAG = "MpvCatalogDiag"
private const val CATALOG_PAGE_SIZE = 100

/** Catalog settings store the built-in default and independently configured HTTP(S) add-ons. */
class CatalogSettings(context: Context) {
  private val prefs = EncryptedSharedPreferences.create(
    context,
    PREFS,
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
  )

  fun catalogSources(): List<CatalogSource> {
    val decoded = prefs.getString("catalog_sources_json", null)?.let { encoded ->
      runCatching { Json.decodeFromString<List<CatalogSource>>(encoded) }.getOrNull()
    }
    val sources = decoded ?: prefs.getStringSet("catalog_sources", null)?.mapNotNull { encoded ->
      val parts = encoded.split("|", limit = 4)
      if (parts.size >= 4 && isHttpAddonEndpoint(parts[2])) {
        CatalogSource(parts[0], parts[1], parts[2], parts[3].toBooleanStrictOrNull() ?: true)
      } else null
    }.orEmpty()
    val savedBuiltin = sources.firstOrNull(::isDefaultBuiltinCatalogSource)
    val configured = sources
      .filterNot { it.id.startsWith("builtin-", ignoreCase = true) }
      .filterNot { it.manifestUrl.trim().equals(BUILTIN_CATALOG_MANIFEST_URL, ignoreCase = true) }
      .filterNot(::isLegacyCatalogSource)
      .filter { isHttpAddonEndpoint(it.manifestUrl) }
      .distinctBy { it.manifestUrl.trim().lowercase(java.util.Locale.ROOT) }
    val builtinRemoved = prefs.getBoolean(BUILTIN_CATALOG_REMOVED_PREF, false)
    val normalized = normalizeCatalogSources(listOfNotNull(savedBuiltin) + configured, builtinRemoved)
    if (normalized != sources) saveCatalogSources(normalized)
    return normalized
  }

  fun saveCatalogSources(value: List<CatalogSource>) {
    val savedBuiltin = value.firstOrNull(::isDefaultBuiltinCatalogSource)
    val configured = value
      .filterNot { it.id.startsWith("builtin-", ignoreCase = true) }
      .filterNot { it.manifestUrl.trim().equals(BUILTIN_CATALOG_MANIFEST_URL, ignoreCase = true) }
      .filterNot(::isLegacyCatalogSource)
      .filter { isHttpAddonEndpoint(it.manifestUrl) }
      .distinctBy { it.manifestUrl.trim().lowercase(java.util.Locale.ROOT) }
    val builtinRemoved = value.none(::isDefaultBuiltinCatalogSource)
    val normalized = normalizeCatalogSources(listOfNotNull(savedBuiltin) + configured, builtinRemoved)
    prefs.edit()
      .putString("catalog_sources_json", Json.encodeToString(normalized))
      .putBoolean(BUILTIN_CATALOG_REMOVED_PREF, builtinRemoved)
      .remove("catalog_sources")
      .apply()
  }

  private fun isLegacyCatalogSource(source: CatalogSource): Boolean =
    source.id != BUILTIN_CATALOG_SOURCE_ID && (
      source.id.startsWith("cinemeta-") || source.id == "kitsu-anime" ||
        source.manifestUrl.contains("v3-cinemeta.strem.io", ignoreCase = true) ||
        source.manifestUrl.contains("anime-kitsu.strem.fun", ignoreCase = true) ||
        source.manifestUrl.contains("cinemeta.ratingposterdb.com", ignoreCase = true)
      )
}

internal fun isHttpAddonEndpoint(value: String): Boolean {
  val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
  return (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
    !uri.host.isNullOrBlank()
}

private fun addonRootUrl(value: String): String =
  value.substringBefore('?').trimEnd('/').removeSuffix("/manifest.json")

private fun addonQuerySuffix(value: String): String =
  value.substringAfter('?', "").let { if (it.isBlank()) "" else "?$it" }

internal fun buildAddonPathUrl(baseOrManifestUrl: String, path: String): String =
  "${addonRootUrl(baseOrManifestUrl)}/${path.trimStart()}${addonQuerySuffix(baseOrManifestUrl)}"

private fun encodeAddonPathSegment(value: String): String =
  URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun addonOriginForLog(value: String): String {
  val scheme = value.substringBefore("://", "https").lowercase()
  val authority = value.substringAfter("://", value).substringBefore('/').substringBefore('?')
  return "$scheme://${authority.substringAfterLast('@')}"
}

internal fun catalogTmdbId(meta: JsonObject): Int? =
  catalogExternalId(meta, "tmdb_id", "tmdbId", "tmdb")?.toIntOrNull()?.takeIf { it > 0 }

internal fun catalogTvdbId(meta: JsonObject): String? =
  catalogExternalId(meta, "tvdb_id", "tvdbId", "tvdb")?.takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }

private fun catalogExternalId(meta: JsonObject, vararg fields: String): String? {
  val containers = listOf(meta, meta["ids"] as? JsonObject)
  return fields.asSequence()
    .flatMap { field -> containers.asSequence().mapNotNull { it?.get(field) } }
    .mapNotNull { value ->
      when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> (value["id"] as? JsonPrimitive)?.contentOrNull
        else -> null
      }
    }
    .firstOrNull { it.isNotBlank() && it != "0" }
}

/** Loads catalog rails and search results from installed Nuvio-compatible catalog add-ons. */
class StremioCatalogRepository {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun validateCatalogManifest(url: String): String = withContext(Dispatchers.IO) {
    val manifest = getJson(url).jsonObject
    val hasCatalogs = manifest["catalogs"]?.jsonArray.orEmpty().isNotEmpty()
    val hasStreams = manifest["resources"]?.jsonArray.orEmpty().any { resource ->
      when (resource) {
        is kotlinx.serialization.json.JsonPrimitive -> resource.contentOrNull.equals("stream", ignoreCase = true)
        is kotlinx.serialization.json.JsonObject -> resource["name"]?.jsonPrimitive?.contentOrNull.equals("stream", ignoreCase = true) ||
          resource["id"]?.jsonPrimitive?.contentOrNull.equals("stream", ignoreCase = true)
        else -> false
      }
    }
    val hasMetadata = manifest["resources"]?.jsonArray.orEmpty().any { resource ->
      when (resource) {
        is kotlinx.serialization.json.JsonPrimitive -> resource.contentOrNull.equals("meta", ignoreCase = true)
        is kotlinx.serialization.json.JsonObject -> resource["name"]?.jsonPrimitive?.contentOrNull.equals("meta", ignoreCase = true) ||
          resource["id"]?.jsonPrimitive?.contentOrNull.equals("meta", ignoreCase = true)
        else -> false
      }
    }
    require(hasCatalogs || hasMetadata || hasStreams) {
      "This manifest exposes no compatible catalogs, metadata, or streams."
    }
    manifest["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: url.substringBefore('?').substringAfterLast('/').removeSuffix(".json").ifBlank { "Catalog add-on" }
  }

  suspend fun isNuvioScraperManifest(url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching { getJson(url).jsonObject["scrapers"]?.jsonArray.orEmpty().isNotEmpty() }.getOrDefault(false)
  }

  suspend fun load(source: CatalogSource, query: String?, page: Int = 1): List<MediaItem> = withContext(Dispatchers.IO) {
    Log.i(DIAG_TAG, "catalog start source=${source.id} queryLength=${query?.length ?: 0} manifest=${addonOriginForLog(source.manifestUrl)}")
    val request = runCatching {
      val manifest = getJson(source.manifestUrl).jsonObject
      val catalogs = manifest["catalogs"]?.jsonArray.orEmpty()
      var attemptedCatalogs = 0
      val failedCatalogs = mutableListOf<String>()
      var successfulCatalogs = 0
      val results = catalogs.filter { catalogElement ->
        // Use the required `search` value during search, but do not guess required genre/year/etc.
        catalogElement.jsonObject["extra"]?.jsonArray.orEmpty().none { extra ->
          val property = extra.jsonObject
          val required = property["isRequired"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
          val name = property["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
          required && !(name.equals("search", ignoreCase = true) && !query.isNullOrBlank()) &&
            !name.equals("skip", ignoreCase = true)
        }
      }.flatMap { catalogElement ->
        runCatching {
          val catalog = catalogElement.jsonObject
          val type = catalog["type"]?.jsonPrimitive?.contentOrNull ?: return@runCatching emptyList()
          val id = catalog["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching emptyList()
          val extras = catalog["extra"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.lowercase() }
          val supportsSearch = "search" in extras
          if (!query.isNullOrBlank() && !supportsSearch) return@runCatching emptyList()
          val supportsPagination = "skip" in extras
          if (page > 1 && !supportsPagination) return@runCatching emptyList()

          val extraParts = buildList {
            if (!query.isNullOrBlank()) add("search=${encodeAddonPathSegment(query)}")
            if (page > 1) add("skip=${(page - 1) * CATALOG_PAGE_SIZE}")
          }
          val extrasPath = extraParts.joinToString("&").takeIf(String::isNotBlank)?.let { "/$it" }.orEmpty()
          val requestUrl = buildAddonPathUrl(
            source.manifestUrl,
            "catalog/$type/${encodeAddonPathSegment(id)}$extrasPath.json",
          )
          attemptedCatalogs++
          val payload = getJson(requestUrl).jsonObject
          successfulCatalogs++
          payload["metas"]?.jsonArray.orEmpty().mapNotNull { element ->
            val meta = element.jsonObject
            val providerId = meta["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val itemType = meta["type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: type.lowercase()
            MediaItem(
              id = providerId.hashCode(),
              type = if (itemType == "movie") MediaType.MOVIE else MediaType.TV,
              title = meta["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
              overview = meta["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
              posterUrl = meta["poster"]?.jsonPrimitive?.contentOrNull,
              backdropUrl = meta["background"]?.jsonPrimitive?.contentOrNull,
              tmdbId = catalogTmdbId(meta),
              imdbId = meta["imdb_id"]?.jsonPrimitive?.contentOrNull,
              tvdbId = catalogTvdbId(meta),
              providerId = providerId,
              catalogSourceId = source.id,
              catalogType = itemType,
              catalogId = id,
              catalogName = catalog["name"]?.jsonPrimitive?.contentOrNull ?: id,
              releaseYear = meta["releaseInfo"]?.jsonPrimitive?.contentOrNull,
              contentRating = meta["imdbRating"]?.jsonPrimitive?.contentOrNull,
              duration = meta["runtime"]?.jsonPrimitive?.contentOrNull,
              genres = meta["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: meta["genre"]?.jsonPrimitive?.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList(),
            )
          }
        }.onFailure { error ->
          if (error is CancellationException) throw error
          failedCatalogs += "${catalogElement.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: catalogElement.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "catalog"}: ${redactAddonConfigurationFromLog(error.message.orEmpty())}"
          Log.w(DIAG_TAG, "catalog request failed source=${source.id}: ${redactAddonConfigurationFromLog(error.message.orEmpty())}")
        }.getOrDefault(emptyList())
      }
      if (attemptedCatalogs == 0 && !query.isNullOrBlank()) {
        error("This catalog add-on does not expose a compatible search catalog.")
      }
      if (attemptedCatalogs > 0 && successfulCatalogs == 0 && failedCatalogs.isNotEmpty()) {
        error("All compatible catalog requests failed: ${failedCatalogs.distinct().joinToString("; ")}")
      }
      results
    }
    request.onSuccess { items -> Log.i(DIAG_TAG, "catalog complete source=${source.id} items=${items.size}") }
      .onFailure { error ->
        if (error is CancellationException) throw error
        Log.w(DIAG_TAG, "catalog manifest failed source=${source.id}: ${redactAddonConfigurationFromLog(error.message.orEmpty())}")
      }
    return@withContext request.getOrThrow()
  }

  private suspend fun getJson(url: String): JsonElement {
    require(isHttpAddonEndpoint(url)) { "Catalog add-on endpoint must be a valid HTTP(S) URL." }
    var attempt = 0
    while (true) {
      val call = client.newCall(Request.Builder().url(url).header("User-Agent", "MpvInfinity/1.0").get().build())
      val result = suspendCancellableCoroutine<JsonElement?> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
          override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
          }

          override fun onResponse(call: Call, response: Response) {
            response.use {
              runCatching {
                if (it.code == 429 && attempt < 3) null
                else if (!it.isSuccessful) error("Catalog request returned HTTP ${it.code}")
                else json.parseToJsonElement(it.body.string())
              }.onSuccess { value -> if (continuation.isActive) continuation.resume(value) }
                .onFailure { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            }
          }
        })
      }
      if (result != null) return result
      val waitMs = 500L shl attempt
      Log.w(DIAG_TAG, "catalog rate limited addon=${addonOriginForLog(url)} retry=${attempt + 1} waitMs=$waitMs")
      delay(waitMs)
      attempt++
    }
  }
}

/** Reads full title and episode metadata from the installed catalog add-ons, like NuvioMobile. */
class StremioMetadataRepository {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun loadMetadata(item: MediaItem, sources: List<CatalogSource>): MediaItem? = withContext(Dispatchers.IO) {
    val orderedSources = requestableCatalogSources(sources).sortedByDescending { it.id == item.catalogSourceId }
    val identifiers = listOfNotNull(item.providerId, item.imdbId).distinct()
    if (identifiers.isEmpty()) return@withContext null
    val defaultType = item.catalogType ?: if (item.type == MediaType.TV) "series" else "movie"
    val types = (listOf(defaultType) + if (item.type == MediaType.TV) listOf("series", "anime") else listOf("movie"))
      .distinct()

    for (source in orderedSources) {
      for (type in types) {
        for (identifier in identifiers) {
          val metadata = runCatching {
            val url = buildAddonPathUrl(source.manifestUrl, "meta/$type/${encodeAddonPathSegment(identifier)}.json")
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "MpvInfinity/1.0").get().build()).execute().use { response ->
              if (!response.isSuccessful) return@use null
              json.parseToJsonElement(response.body.string()).jsonObject["meta"]?.jsonObject
            }
          }.getOrNull() ?: continue

          val metaPoster = metadata["poster"]?.jsonPrimitive?.contentOrNull
          val metaBackground = metadata["background"]?.jsonPrimitive?.contentOrNull
          val appExtras = metadata["app_extras"] as? JsonObject
          val seasons = parseSeasons(
            metadata["videos"] as? JsonArray,
            metadata["seasons"] as? JsonArray,
            metaPoster,
            metaBackground,
            appExtras,
          )
          val releaseInfo = metadata["releaseInfo"]?.jsonPrimitive?.contentOrNull
          return@withContext item.copy(
            title = metadata["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: item.title,
            overview = metadata["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: item.overview,
            posterUrl = metadata["poster"]?.jsonPrimitive?.contentOrNull ?: item.posterUrl,
            backdropUrl = metadata["background"]?.jsonPrimitive?.contentOrNull ?: item.backdropUrl,
            tmdbId = catalogTmdbId(metadata) ?: item.tmdbId,
            imdbId = metadata["imdb_id"]?.jsonPrimitive?.contentOrNull ?: item.imdbId,
            tvdbId = catalogTvdbId(metadata) ?: item.tvdbId,
            releaseYear = releaseInfo ?: item.releaseYear,
            contentRating = metadata["imdbRating"]?.jsonPrimitive?.contentOrNull ?: item.contentRating,
            duration = metadata["runtime"]?.jsonPrimitive?.contentOrNull ?: item.duration,
            genres = metadata["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.ifEmpty { item.genres } ?: item.genres,
            seasons = seasons.ifEmpty { item.seasons },
          )
        }
      }
    }
    null
  }

  suspend fun loadSeasons(item: MediaItem, sources: List<CatalogSource>): List<Season> =
    loadMetadata(item, sources)?.seasons.orEmpty()

  internal fun parseSeasons(
    videos: JsonArray?,
    seasonObjects: JsonArray?,
    metaPoster: String?,
    metaBackground: String?,
    appExtras: JsonObject?,
  ): List<Season> {
    val topLevelPosters = seasonObjects.orEmpty().mapNotNull { element ->
      val season = element.jsonObject
      val number = season["season_number"]?.jsonPrimitive?.intOrNull
        ?: season["number"]?.jsonPrimitive?.intOrNull
        ?: season["season"]?.jsonPrimitive?.intOrNull
        ?: return@mapNotNull null
      if (!isPlausibleSeasonNumber(number)) return@mapNotNull null
      val poster = season["poster"]?.jsonPrimitive?.contentOrNull
        ?: season["poster_path"]?.jsonPrimitive?.contentOrNull
        ?: season["posterPath"]?.jsonPrimitive?.contentOrNull
        ?: season["posterUrl"]?.jsonPrimitive?.contentOrNull
        ?: season["seasonPoster"]?.jsonPrimitive?.contentOrNull
        ?: season["season_poster"]?.jsonPrimitive?.contentOrNull
        ?: season["image"]?.jsonPrimitive?.contentOrNull
        ?: season["thumbnail"]?.jsonPrimitive?.contentOrNull
        ?: season["background"]?.jsonPrimitive?.contentOrNull
      number to poster?.let(::normalizeSeasonArtwork)
    }.toMap()

    val episodePosters = videos.orEmpty().mapNotNull { element ->
      val video = element.jsonObject
      val season = video["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      if (!isPlausibleSeasonNumber(season)) return@mapNotNull null
      val poster = video["seasonPoster"]?.jsonPrimitive?.contentOrNull
        ?: video["season_poster_path"]?.jsonPrimitive?.contentOrNull
        ?: video["season_poster"]?.jsonPrimitive?.contentOrNull
        // Some metadata add-ons expose only a per-episode thumbnail. Use the
        // first available thumbnail as that season's artwork fallback.
        ?: video["thumbnail"]?.jsonPrimitive?.contentOrNull
        ?: return@mapNotNull null
      season to normalizeSeasonArtwork(poster)
    }.toMap()

    // Match NuvioMobile precedence: the add-on's keyed/array app_extras map is
    // authoritative, then per-video seasonPoster, then top-level season fields.
    // The previous order let a generic top-level poster overwrite every season.
    val explicitPosters = topLevelPosters + episodePosters + parseAppExtrasSeasonPosters(appExtras, videos)
    val seasonsFromVideos = videos.orEmpty().mapNotNull { element ->
    val video = element.jsonObject
    val season = video["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
    if (!isPlausibleSeasonNumber(season)) return@mapNotNull null
    val episode = video["episode"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
    Season(
      season,
      listOf(
        Episode(
          number = episode,
          title = video["name"]?.jsonPrimitive?.contentOrNull
            ?: video["title"]?.jsonPrimitive?.contentOrNull
            ?: "Episode $episode",
          overview = video["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
          stillUrl = video["thumbnail"]?.jsonPrimitive?.contentOrNull,
          seasonPosterUrl = video["seasonPoster"]?.jsonPrimitive?.contentOrNull
            ?: video["season_poster_path"]?.jsonPrimitive?.contentOrNull
            ?: video["season_poster"]?.jsonPrimitive?.contentOrNull,
          runtime = video["runtime"]?.jsonPrimitive?.contentOrNull,
          videoId = video["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
        ),
      ),
    )
    }.groupBy { it.number }
      .map { (number, seasons) ->
        val episodes = seasons.flatMap { it.episodes }.distinctBy { it.number }.sortedBy { it.number }
        // Prefer explicit season artwork; the parser above supplies the first
        // add-on thumbnail only when the provider has no season-poster field.
        Season(number, episodes, explicitPosters[number])
      }
    val seasonNumbers = (seasonsFromVideos.map { it.number } + explicitPosters.keys).distinct().sorted()
    return seasonNumbers.map { number ->
      seasonsFromVideos.firstOrNull { it.number == number } ?: Season(number, posterUrl = explicitPosters[number])
    }
  }

  private fun parseAppExtrasSeasonPosters(appExtras: JsonObject?, videos: JsonArray?): Map<Int, String> {
    val value = appExtras?.get("seasonPosters") ?: appExtras?.get("seasonPosterByNumber") ?: return emptyMap()
    if (value is JsonObject) {
      return value.mapNotNull { (key, poster) ->
        val number = key.toIntOrNull() ?: return@mapNotNull null
      poster.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { number to normalizeSeasonArtwork(it) }
      }.toMap()
    }
    val posters = value as? JsonArray ?: return emptyMap()
    val seasons = videos.orEmpty().mapNotNull { it.jsonObject["season"]?.jsonPrimitive?.intOrNull }.distinct().sorted()
    val positiveSeasons = seasons.filter { it > 0 }
    val mappedSeasons = when {
      seasons.size == posters.size -> seasons
      positiveSeasons.size == posters.size -> positiveSeasons
      posters.firstOrNull() == JsonNull && positiveSeasons.size + 1 == posters.size -> listOf(0) + positiveSeasons
      else -> List(posters.size) { it + 1 }
    }
    return posters.mapIndexedNotNull { index, element ->
      val poster = (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
      mappedSeasons.getOrNull(index)?.takeIf { it > 0 }?.let { it to normalizeSeasonArtwork(poster) }
    }.toMap()
  }

  private fun normalizeSeasonArtwork(value: String): String = when {
    value.startsWith("/") -> "https://image.tmdb.org/t/p/original$value"
    else -> highQualityPosterUrl(value)
  }

  /** Add-ons occasionally put an air year in video.season; never expose it as S1999. */
  private fun isPlausibleSeasonNumber(number: Int): Boolean = number in 0..100
}
