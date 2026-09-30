package app.infinity.mpvz.catalog.nuvio

import android.util.Log
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.Season
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal fun tvdbSeriesPosterUrl(series: JsonObject): String? =
  series["image"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.replace("\\/", "/")

/** Direct TVDB v4 season artwork provider. TVDB is deliberately primary; callers own fallback policy. */
internal class TvdbMetadataRepository {
  private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()
  private val json = Json { ignoreUnknownKeys = true }
  private val responseCache = ConcurrentHashMap<String, CachedResponse>()
  @Volatile private var cachedToken: CachedToken? = null

  suspend fun load(item: MediaItem, apiKey: String): MediaItem? = withContext(Dispatchers.IO) {
    if (item.type != MediaType.TV || apiKey.isBlank()) return@withContext null
    runCatching {
      withTimeout(45_000L) {
        val auth = bearer(apiKey) ?: return@withTimeout null
        val seriesId = resolveSeriesId(item, auth) ?: return@withTimeout null
        Log.i(TAG, "TVDB resolving title=${item.title} series=$seriesId")
        val seriesEnvelope = get("/series/$seriesId/extended", auth)
        val series = seriesEnvelope["data"]?.jsonObject ?: seriesEnvelope
        val records = series["seasons"]?.jsonArray.orEmpty()
          .mapNotNull { it.jsonObject.toSeasonRef() }
          .filter { it.number > 0 }
          // TVDB can return several records for one number (Aired, DVD,
          // Absolute and alternate orders). Do not keep the first record:
          // that record can be an artwork-less alternate season.
          .groupBy { it.number }
          .values
          .map { candidates ->
            candidates.maxWithOrNull(compareBy<SeasonRef> {
              if (it.typeName.equals("Aired Order", true)) 100 else 0
            }.thenBy {
              if (!it.image.isNullOrBlank()) 10 else 0
            }.thenBy { it.id })!!
          }
          .sortedBy { it.number }
        if (records.isEmpty()) {
          Log.w(TAG, "TVDB returned no usable season records title=${item.title} series=$seriesId keys=${series.keys.joinToString()}")
          return@withTimeout null
        }
        val semaphore = Semaphore(4)
        val enriched = coroutineScope {
          records.map { ref ->
            async(Dispatchers.IO) {
              semaphore.withPermit {
                val direct = ref.image
                if (!direct.isNullOrBlank()) {
                  Season(ref.number, posterUrl = normalizeImage(direct))
                } else {
                  val extendedEnvelope = runCatching { get("/seasons/${ref.id}/extended", auth) }.getOrNull()
                  val extended = extendedEnvelope?.get("data")?.jsonObject ?: extendedEnvelope
                  val image = extended?.string("image")
                    ?: extended?.get("artwork")?.jsonArray.orEmpty().firstNotNullOfOrNull { it.jsonObject.string("image") }
                  Season(ref.number, posterUrl = image?.let(::normalizeImage))
                }
              }
            }
          }.awaitAll().sortedBy { it.number }
        }
        val usable = enriched.count { !it.posterUrl.isNullOrBlank() }
        if (usable == 0) {
          Log.w(TAG, "TVDB returned season records but no artwork title=${item.title} series=$seriesId records=${records.joinToString { "${it.number}:${it.id}:${it.typeName}" }}")
          return@withTimeout null
        }
        Log.i(TAG, "TVDB season artwork title=${item.title} series=$seriesId seasons=${enriched.size} posters=$usable urls=${enriched.filter { !it.posterUrl.isNullOrBlank() }.joinToString { "S${it.number}:${it.posterUrl!!.substringAfterLast('/')}" }}")
        // Return only TVDB's season records. The ViewModel merges these poster
        // values into the existing episodes; returning the old seasons here
        // would make an old thumbnail look like a TVDB result.
        item.copy(seasons = enriched)
      }
    }.onFailure { error ->
      if (error !is kotlinx.coroutines.CancellationException) {
        Log.w(TAG, "TVDB season artwork failed title=${item.title}: ${error.message.orEmpty()}")
      }
    }.getOrNull()
  }

  /** Lightweight title-poster lookup for Stream home/search; season artwork is handled separately. */
  suspend fun loadTitleArtwork(item: MediaItem, apiKey: String): MediaItem? = withContext(Dispatchers.IO) {
    if (item.type != MediaType.TV || apiKey.isBlank() || !item.posterUrl.isNullOrBlank()) return@withContext null
    try {
      withTimeout(45_000L) {
        val auth = bearer(apiKey) ?: return@withTimeout null
        val seriesId = resolveSeriesId(item, auth) ?: return@withTimeout null
        val envelope = get("/series/$seriesId/extended", auth)
        val series = envelope["data"]?.jsonObject ?: envelope
        val poster = tvdbSeriesPosterUrl(series) ?: return@withTimeout null
        item.copy(posterUrl = poster, tvdbId = item.tvdbId ?: seriesId)
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      null
    }
  }

  private fun bearer(apiKey: String): String? {
    val now = System.currentTimeMillis()
    val keyFingerprint = apiKey.sha256()
    cachedToken?.takeIf {
      it.keyFingerprint == keyFingerprint && it.expiresAt > now + TOKEN_REFRESH_MARGIN_MS
    }?.let { return it.value }
    val body = "{\"apikey\":\"${escapeJson(apiKey)}\"}"
    val request = Request.Builder().url(BASE + "/login")
      .post(okhttp3.RequestBody.create(JSON, body))
      .header("Accept", "application/json")
      .header("Content-Type", "application/json")
      .build()
    return requestWithRetry(request, auth = null)?.jsonObject?.get("data")?.jsonObject?.string("token")?.also {
      cachedToken = CachedToken(keyFingerprint, it, now + TOKEN_TTL_MS)
    }
  }

  private fun resolveSeriesId(item: MediaItem, auth: String): String? {
    item.tvdbId?.trim()?.takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }?.let { return it }
    val provider = item.providerId.orEmpty()
    Regex("(?:tvdb[:/]|thetvdb[:/]|tvdb-id[:/])(\\d+)", RegexOption.IGNORE_CASE).find(provider)?.groupValues?.getOrNull(1)?.let { return it }
    val query = URLEncoder.encode(item.title, "UTF-8")
    val year = item.releaseYear?.take(4)?.takeIf { it.length == 4 }?.let { "&year=$it" }.orEmpty()
    val results = get("/search?query=$query&type=series$year", auth)["data"]?.jsonArray.orEmpty()
    val expected = normalize(item.title)
    return results.mapNotNull { element ->
      val row = element.jsonObject
      val id = row.string("id")?.substringAfterLast('-')?.toLongOrNull() ?: return@mapNotNull null
      val name = normalize(row.string("name").orEmpty())
      val rowYear = row.string("year")
      val score = (if (name == expected) 1000 else 0) +
        (if (rowYear == item.releaseYear?.take(4)) 150 else 0) +
        prefixScore(name, expected)
      id to score
    }.maxByOrNull { it.second }?.first?.toString()
  }

  private fun get(path: String, auth: String): JsonObject {
    val url = BASE + path
    val cached = responseCache[url]?.takeIf { it.expiresAt > System.currentTimeMillis() }
    if (cached != null) return cached.body.jsonObject
    val request = Request.Builder().url(url).get()
      .header("Accept", "application/json")
      .header("Authorization", "Bearer $auth")
      .header("User-Agent", "Mpv-infinity TVDB metadata")
      .build()
    val body = requestWithRetry(request, auth)?.jsonObject ?: error("TVDB request failed")
    responseCache[url] = CachedResponse(System.currentTimeMillis() + CACHE_TTL_MS, body)
    return body
  }

  private fun requestWithRetry(request: Request, auth: String?): JsonElement? {
    var attempt = 0
    while (true) {
      try {
        val actual = request.newBuilder().apply { if (auth != null) header("Authorization", "Bearer $auth") }.build()
        http.newCall(actual).execute().use { response ->
          val text = response.body.string()
          if ((response.code == 401 || response.code == 403) && auth != null) {
            if (cachedToken?.value == auth) cachedToken = null
          }
          if (response.code == 429 || response.code == 408 || response.code in 500..599) {
            if (attempt < MAX_RETRIES) {
              Thread.sleep(RETRY_DELAYS_MS[attempt++])
              continue
            }
          }
          if (!response.isSuccessful) error("TVDB HTTP ${response.code}")
          return json.parseToJsonElement(text)
        }
      } catch (error: java.io.IOException) {
        if (attempt < MAX_RETRIES) {
          Thread.sleep(RETRY_DELAYS_MS[attempt++])
        } else throw error
      }
    }
  }

  private data class SeasonRef(val id: Long, val number: Int, val image: String?, val typeName: String?)
  private data class CachedToken(val keyFingerprint: String, val value: String, val expiresAt: Long)
  private data class CachedResponse(val expiresAt: Long, val body: JsonElement)

  private fun JsonObject.toSeasonRef(): SeasonRef? = SeasonRef(
    id = string("id")?.toLongOrNull() ?: return null,
    number = string("number")?.toIntOrNull() ?: return null,
    image = string("image"),
    typeName = get("type")?.jsonObject?.string("name"),
  )

  private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
  private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
  private fun prefixScore(a: String, b: String): Int = a.zip(b).takeWhile { it.first == it.second }.size
  private fun normalizeImage(value: String) = value.replace("\\/", "/")
  private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  private fun escapeJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
  private companion object {
    const val BASE = "https://api4.thetvdb.com/v4"
    const val TAG = "MpvCatalogDiag"
    const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L
    const val TOKEN_TTL_MS = 30L * 24 * 60 * 60 * 1_000L
    const val TOKEN_REFRESH_MARGIN_MS = 24 * 60 * 60 * 1_000L
    const val MAX_RETRIES = 3
    val RETRY_DELAYS_MS = longArrayOf(750L, 1_500L, 3_000L)
    val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
