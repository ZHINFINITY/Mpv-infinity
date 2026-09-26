package app.infinity.mpvz.catalog

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Resolves standard Stremio HTTP stream resources without requiring TMDB credentials. */
class StremioStreamRepository {
  companion object {
    private const val TAG = "MpvCatalogDiag"
  }
  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .build()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun resolve(
    item: MediaItem,
    season: Int?,
    episode: Int?,
    sources: List<CatalogSource>,
  ): List<StreamOption> = withContext(Dispatchers.IO) {
    val identifier = stremioId(item, season, episode)
    if (identifier == null) {
      Log.w(TAG, "stream skipped: no IMDb-compatible id title=${item.title} type=${item.type} provider=${item.providerId.orEmpty()}")
      return@withContext emptyList()
    }
    val type = if (item.type == MediaType.MOVIE) "movie" else "series"
    sources.filter { it.isEnabled }.map { source ->
      kotlinx.coroutines.coroutineScope {
        async {
          runCatching {
            val url = "${source.manifestUrl.substringBefore('?').trimEnd('/').removeSuffix("/manifest.json")}" +
              "/stream/$type/${encodeAddonPathSegment(identifier)}.json"
            Log.i(TAG, "stream request source=${source.id} type=$type id=$identifier origin=${addonOriginForLog(source.manifestUrl)}")
            val payload = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
              if (!response.isSuccessful) {
                Log.w(TAG, "stream response source=${source.id} http=${response.code}")
                return@use emptyList<StreamOption>()
              }
              val streams = json.parseToJsonElement(response.body.string()).jsonObject["streams"]?.jsonArray.orEmpty().mapNotNull { element ->
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
              }
              Log.i(TAG, "stream response source=${source.id} links=${streams.size}")
              streams
            }
            payload
          }.onFailure { error ->
            Log.w(TAG, "stream request failed source=${source.id}: ${redactAddonConfigurationFromLog(error.message.orEmpty())}")
          }.getOrDefault(emptyList())
        }
      }
    }.awaitAll().flatten().distinctBy { it.url }
      .sortedWith(compareByDescending<StreamOption> { it.qualityRank }.thenBy { it.source.orEmpty() })
  }

  private fun stremioId(item: MediaItem, season: Int?, episode: Int?): String? {
    val raw = listOfNotNull(item.imdbId, item.providerId).map { it.trim() }.firstOrNull { it.startsWith("tt", true) }
      ?: return null
    val id = raw.substringBefore(':').substringBefore('/')
    return if (item.type == MediaType.TV && season != null && episode != null) "$id:$season:$episode" else id
  }

  private fun encodeAddonPathSegment(value: String): String =
    value.split(':').joinToString(":") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
