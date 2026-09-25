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
private const val DIAG_TAG = "MpvCatalogDiag"
private const val CATALOG_PAGE_SIZE = 100

/** Catalog feeds are user-installed Nuvio-compatible/Stremio catalog add-ons; there are no bundled legacy catalogs. */
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
    val cleaned = sources
      .filterNot(::isLegacyCatalogSource)
      .filter { isHttpAddonEndpoint(it.manifestUrl) }
      .distinctBy { it.manifestUrl.lowercase() }
    if (cleaned != sources) saveCatalogSources(cleaned)
    return cleaned
  }

  fun saveCatalogSources(value: List<CatalogSource>) {
    val validSources = value
      .filterNot(::isLegacyCatalogSource)
      .filter { isHttpAddonEndpoint(it.manifestUrl) }
      .distinctBy { it.manifestUrl.lowercase() }
    prefs.edit()
      .putString("catalog_sources_json", Json.encodeToString(validSources))
      .remove("catalog_sources")
      .apply()
  }

  private fun isLegacyCatalogSource(source: CatalogSource): Boolean =
    source.id.startsWith("cinemeta-") || source.id == "kitsu-anime" ||
      source.manifestUrl.contains("v3-cinemeta.strem.io", ignoreCase = true) ||
      source.manifestUrl.contains("anime-kitsu.strem.fun", ignoreCase = true)
}

internal fun isHttpAddonEndpoint(value: String): Boolean =
  value.trim().startsWith("https://", true) || value.trim().startsWith("http://", true)

private fun addonRootUrl(value: String): String =
  value.substringBefore('?').trimEnd('/').removeSuffix("/manifest.json")

private fun addonQuerySuffix(value: String): String =
  value.substringAfter('?', "").let { if (it.isBlank()) "" else "?$it" }

private fun buildAddonPathUrl(baseOrManifestUrl: String, path: String): String =
  "${addonRootUrl(baseOrManifestUrl)}/${path.trimStart()}${addonQuerySuffix(baseOrManifestUrl)}"

private fun encodeAddonPathSegment(value: String): String =
  URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun addonOriginForLog(value: String): String {
  val scheme = value.substringBefore("://", "https").lowercase()
  val authority = value.substringAfter("://", value).substringBefore('/').substringBefore('?')
  return "$scheme://${authority.substringAfterLast('@')}"
}

/** Loads catalog rails and search results from installed Nuvio-compatible catalog add-ons. */
class StremioCatalogRepository {
  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun validateCatalogManifest(url: String): String = withContext(Dispatchers.IO) {
    val manifest = getJson(url).jsonObject
    val hasCatalogs = manifest["catalogs"]?.jsonArray.orEmpty().isNotEmpty()
    val hasMetadata = manifest["resources"]?.jsonArray.orEmpty().any { resource ->
      when (resource) {
        is kotlinx.serialization.json.JsonPrimitive -> resource.contentOrNull.equals("meta", ignoreCase = true)
        is kotlinx.serialization.json.JsonObject -> resource["name"]?.jsonPrimitive?.contentOrNull.equals("meta", ignoreCase = true) ||
          resource["id"]?.jsonPrimitive?.contentOrNull.equals("meta", ignoreCase = true)
        else -> false
      }
    }
    require(hasCatalogs || hasMetadata) {
      "This manifest exposes neither catalogs nor metadata. Install JavaScript scraper repositories under Nuvio JavaScript Providers instead."
    }
    manifest["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: url.substringBefore('?').substringAfterLast('/').removeSuffix(".json").ifBlank { "Catalog add-on" }
  }

  suspend fun isNuvioScraperManifest(url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching { getJson(url).jsonObject["scrapers"]?.jsonArray.orEmpty().isNotEmpty() }.getOrDefault(false)
  }

  suspend fun load(source: CatalogSource, query: String?, page: Int = 1): List<MediaItem> = withContext(Dispatchers.IO) {
    Log.i(DIAG_TAG, "catalog start source=${source.id} queryLength=${query?.length ?: 0} manifest=${addonOriginForLog(source.manifestUrl)}")
    runCatching {
      val manifest = getJson(source.manifestUrl).jsonObject
      val catalogs = manifest["catalogs"]?.jsonArray.orEmpty()
      catalogs.filter { catalogElement ->
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
          val payload = getJson(requestUrl).jsonObject
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
              imdbId = meta["imdb_id"]?.jsonPrimitive?.contentOrNull,
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
          Log.w(DIAG_TAG, "catalog request failed source=${source.id}: ${redactAddonConfigurationFromLog(error.message.orEmpty())}")
        }.getOrDefault(emptyList())
      }
    }.onSuccess { items -> Log.i(DIAG_TAG, "catalog complete source=${source.id} items=${items.size}") }
      .onFailure { error ->
        if (error is CancellationException) throw error
        Log.w(DIAG_TAG, "catalog manifest failed source=${source.id}: ${redactAddonConfigurationFromLog(error.message.orEmpty())}")
      }
      .getOrDefault(emptyList())
  }

  private suspend fun getJson(url: String): JsonElement {
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
    val orderedSources = sources.filter { it.isEnabled }.sortedByDescending { it.id == item.catalogSourceId }
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

          val seasons = parseSeasons(metadata["videos"] as? JsonArray)
          val releaseInfo = metadata["releaseInfo"]?.jsonPrimitive?.contentOrNull
          return@withContext item.copy(
            title = metadata["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: item.title,
            overview = metadata["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: item.overview,
            posterUrl = metadata["poster"]?.jsonPrimitive?.contentOrNull ?: item.posterUrl,
            backdropUrl = metadata["background"]?.jsonPrimitive?.contentOrNull ?: item.backdropUrl,
            imdbId = metadata["imdb_id"]?.jsonPrimitive?.contentOrNull ?: item.imdbId,
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

  private fun parseSeasons(videos: JsonArray?): List<Season> = videos.orEmpty().mapNotNull { element ->
    val video = element.jsonObject
    val season = video["season"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
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
          runtime = video["runtime"]?.jsonPrimitive?.contentOrNull,
        ),
      ),
    )
  }.groupBy { it.number }
    .map { (number, seasons) -> Season(number, seasons.flatMap { it.episodes }.distinctBy { it.number }.sortedBy { it.number }) }
    .sortedBy { it.number }
}
