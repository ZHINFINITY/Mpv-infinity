package app.infinity.mpvz.catalog

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Resolves Stremio streams using each add-on's declared resource and ID capabilities. */
class StremioStreamRepository {
  companion object {
    private const val TAG = "MpvCatalogDiag"
  }

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .build()
  private val manifestClient = client.newBuilder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.SECONDS)
    .build()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun resolve(
    item: MediaItem,
    season: Int?,
    episode: Int?,
    sources: List<CatalogSource>,
    episodeVideoId: String? = null,
    resolveImdbId: suspend (MediaItem) -> String? = { null },
    onBatch: suspend (List<StreamOption>) -> Unit = {},
  ): List<StreamOption> = withContext(Dispatchers.IO) {
    val enabledSources = sources.filter { it.isEnabled }
    if (enabledSources.isEmpty()) return@withContext emptyList()

    val directIdentifier = stremioImdbIdentifier(item, season, episode, episodeVideoId)
    val mappingMutex = Mutex()
    var mappingAttempted = false
    var mappedFallback: String? = null
    suspend fun resolveMappedFallback(): String? {
      mappingMutex.lock()
      try {
        if (!mappingAttempted) {
          mappingAttempted = true
          val mappedImdbId = try {
            resolveImdbId(item)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            null
          }
          mappedFallback = stremioImdbIdentifier(item, season, episode, episodeVideoId, mappedImdbId)
        }
        return mappedFallback
      } finally {
        mappingMutex.unlock()
      }
    }

    // Each source fetches its own manifest and stream independently, so a slow
    // manifest or ID mapping cannot hold links returned by another source.
    coroutineScope {
      val results = enabledSources.map { source ->
        async {
          resolveSource(
            item = item,
            source = source,
            season = season,
            episode = episode,
            episodeVideoId = episodeVideoId,
            directIdentifier = directIdentifier,
            resolveMappedFallback = ::resolveMappedFallback,
            onBatch = onBatch,
          )
        }
      }.awaitAll().flatten()
      if (results.isEmpty()) Log.w(TAG, "stream resolution produced no compatible links")
      results.distinctBy { it.url }
        .sortedWith(compareByDescending<StreamOption> { it.qualityRank }.thenBy { it.source.orEmpty() })
    }
  }

  private suspend fun resolveSource(
    item: MediaItem,
    source: CatalogSource,
    season: Int?,
    episode: Int?,
    episodeVideoId: String?,
    directIdentifier: String?,
    resolveMappedFallback: suspend () -> String?,
    onBatch: suspend (List<StreamOption>) -> Unit,
  ): List<StreamOption> {
    val manifest = loadManifest(source)
    var identifier = selectStreamIdentifierForSource(
      item = item,
      source = source,
      manifest = manifest,
      season = season,
      episode = episode,
      episodeVideoId = episodeVideoId,
      fallbackIdentifier = directIdentifier,
    )
    if (identifier == null && directIdentifier == null &&
      (manifest == null || manifestDeclaresStreamResource(manifest, stremioType(item)))
    ) {
      identifier = selectStreamIdentifierForSource(
        item = item,
        source = source,
        manifest = manifest,
        season = season,
        episode = episode,
        episodeVideoId = episodeVideoId,
        fallbackIdentifier = resolveMappedFallback(),
      )
    }
    if (identifier == null) return emptyList()

    val streams = try {
      val url = stremioStreamRequestUrl(source.manifestUrl, stremioType(item), identifier)
      client.newCall(
        Request.Builder().url(url).header("Accept", "application/json").build(),
      ).execute().use { response ->
        if (!response.isSuccessful) return@use emptyList<StreamOption>()
        json.parseToJsonElement(response.body.string()).jsonObject["streams"]?.jsonArray.orEmpty()
          .mapNotNull { element ->
            val stream = element.jsonObject
            val externalUrl = stream["externalUrl"]?.jsonPrimitive?.contentOrNull
            val streamUrl = stream["url"]?.jsonPrimitive?.contentOrNull
              ?: externalUrl
              ?: return@mapNotNull null
            if (!streamUrl.startsWith("http://", true) && !streamUrl.startsWith("https://", true)) return@mapNotNull null
            val title = stream["title"]?.jsonPrimitive?.contentOrNull
              ?: stream["name"]?.jsonPrimitive?.contentOrNull
              ?: source.name
            val behavior = stream["behaviorHints"]?.jsonObject
            val proxyHeaders = behavior?.get("proxyHeaders")?.jsonObject?.let { root ->
              root["request"]?.jsonObject ?: root
            }
            StreamOption(
              url = streamUrl,
              title = title,
              headers = proxyHeaders?.mapNotNull { (key, value) ->
                value.jsonPrimitive.contentOrNull?.let { key to it }
              }?.toMap().orEmpty(),
              qualityRank = Regex("(\\d{3,4})\\s*p?", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
              source = source.name,
              isPlayable = externalUrl == null,
              isExternal = externalUrl != null,
              season = season,
              episode = episode,
            )
          }.distinctBy { it.url }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      Log.w(TAG, "stream request failed for an enabled add-on")
      emptyList()
    }
    if (streams.isNotEmpty()) onBatch(streams)
    return streams
  }

  private fun loadManifest(source: CatalogSource): JsonObject? = runCatching {
    manifestClient.newCall(
      Request.Builder().url(source.manifestUrl).header("Accept", "application/json").build(),
    ).execute().use { response ->
      if (!response.isSuccessful) null else json.parseToJsonElement(response.body.string()).jsonObject
    }
  }.getOrNull()
}

/** Checks whether a manifest declares a stream resource for the requested media type. */
internal fun manifestDeclaresStreamResource(manifest: JsonObject?, type: String): Boolean {
  val resources = manifest?.get("resources") as? JsonArray ?: return false
  return resources.any { resource ->
    manifestResourceName(resource).equals("stream", ignoreCase = true) && manifestResourceSupportsType(resource, type)
  }
}

/** Checks the Stremio manifest resource name, content type, and optional ID-prefix filter. */
internal fun manifestSupportsStreamId(manifest: JsonObject?, type: String, id: String): Boolean {
  val resources = manifest?.get("resources") as? JsonArray ?: return false
  return resources.any { resource ->
    if (!manifestResourceName(resource).equals("stream", ignoreCase = true)) return@any false
    if (!manifestResourceSupportsType(resource, type)) return@any false
    val idPrefixes = (resource as? JsonObject)?.get("idPrefixes") as? JsonArray
    idPrefixes.isNullOrEmpty() || idPrefixes.any { entry ->
      (entry as? JsonPrimitive)?.contentOrNull?.let { prefix -> id.startsWith(prefix) } == true
    }
  }
}

private fun manifestResourceName(resource: JsonElement): String? = when (resource) {
  is JsonPrimitive -> resource.contentOrNull
  is JsonObject -> (resource["name"] as? JsonPrimitive)?.contentOrNull
    ?: (resource["id"] as? JsonPrimitive)?.contentOrNull
  else -> null
}

private fun manifestResourceSupportsType(resource: JsonElement, type: String): Boolean {
  val declaredTypes = (resource as? JsonObject)?.get("types") as? JsonArray ?: return true
  return declaredTypes.isEmpty() || declaredTypes.any { entry ->
    (entry as? JsonPrimitive)?.contentOrNull?.equals(type, ignoreCase = true) == true
  }
}

/** IMDb IDs use the standard Stremio series suffix; non-IMDb video IDs remain untouched for their origin add-on. */
internal fun stremioImdbIdentifier(
  item: MediaItem,
  season: Int?,
  episode: Int?,
  episodeVideoId: String? = null,
  mappedImdbId: String? = null,
): String? {
  val raw = listOfNotNull(item.imdbId, item.providerId, episodeVideoId, mappedImdbId)
    .map { it.trim() }
    .firstOrNull { candidate -> candidate.substringBefore(':').substringBefore('/').startsWith("tt", true) }
    ?: return null
  val id = raw.substringBefore(':').substringBefore('/')
  return if (item.type == MediaType.TV && season != null && episode != null) "$id:$season:$episode" else id
}

/** Uses the selected add-on's exact video ID only when its own stream manifest accepts it. */
internal fun selectStreamIdentifierForSource(
  item: MediaItem,
  source: CatalogSource,
  manifest: JsonObject?,
  season: Int?,
  episode: Int?,
  episodeVideoId: String?,
  fallbackIdentifier: String?,
): String? {
  if (source.id == item.catalogSourceId) {
    val explicitVideoIdentifier = episodeVideoId?.trim()?.takeIf(String::isNotBlank)
    val originIdentifier = when {
      item.type == MediaType.TV && season != null && episode != null -> explicitVideoIdentifier
      explicitVideoIdentifier != null -> explicitVideoIdentifier
      else -> listOfNotNull(item.providerId, item.imdbId).map { it.trim() }.firstOrNull { it.isNotBlank() }
    }
    if (originIdentifier != null && manifestSupportsStreamId(manifest, stremioType(item), originIdentifier)) {
      return originIdentifier
    }
  }
  if (manifest == null) return fallbackIdentifier
  return fallbackIdentifier?.takeIf { manifestSupportsStreamId(manifest, stremioType(item), it) }
}

private fun stremioType(item: MediaItem): String = if (item.type == MediaType.MOVIE) "movie" else "series"

internal fun stremioStreamRequestUrl(manifestUrl: String, type: String, id: String): String =
  buildAddonPathUrl(manifestUrl, "stream/$type/${encodeStreamIdSegment(id)}.json")

private fun encodeStreamIdSegment(value: String): String =
  value.split(':').joinToString(":") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
