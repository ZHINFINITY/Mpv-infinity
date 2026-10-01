package app.infinity.mpvz.catalog.nuvio

import android.util.Log
import app.infinity.mpvz.catalog.Episode
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.Season
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

internal fun tmdbMediaKind(item: MediaItem): String =
  if (item.type == MediaType.MOVIE) "movie" else "tv"

/** TMDB-backed details/episodes path used by NuvioMobile. Falls back to catalog metadata when no user API key is configured. */
internal class TmdbMetadataRepository {
  private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }
  private val responseCache = ConcurrentHashMap<String, CachedResponse>()

  /** Fetch only title artwork for home/search cards; do not load every season and episode. */
  suspend fun loadPosterArtwork(item: MediaItem, apiKey: String): MediaItem? {
    if (apiKey.isBlank()) return null
    return withContext(Dispatchers.IO) {
      try {
        val kind = tmdbMediaKind(item)
        val year = item.releaseYear?.take(4)?.takeIf { it.length == 4 }
        val directId = explicitTmdbId(item)
        val searchResult = if (directId == null) searchTmdbResult(item.title, year, kind, apiKey) else null
        val id = directId ?: searchResult?.get("id")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
          ?: return@withContext null
        val searchPoster = searchResult?.string("poster_path")
        val searchBackdrop = searchResult?.string("backdrop_path")
        if (searchPoster != null || searchBackdrop != null) {
          return@withContext item.copy(
            posterUrl = searchPoster?.let { "https://image.tmdb.org/t/p/original$it" } ?: item.posterUrl,
            backdropUrl = searchBackdrop?.let { "https://image.tmdb.org/t/p/original$it" } ?: item.backdropUrl,
            tmdbId = item.tmdbId ?: id,
          )
        }
        val detail = get("https://api.themoviedb.org/3/$kind/$id?api_key=${enc(apiKey)}")
        val posterPath = detail.string("poster_path")
        val backdropPath = detail.string("backdrop_path")
        if (posterPath == null && backdropPath == null) return@withContext null
        item.copy(
          posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/original$it" } ?: item.posterUrl,
          backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" } ?: item.backdropUrl,
          tmdbId = item.tmdbId ?: id,
        )
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        null
      }
    }
  }

  suspend fun load(item: MediaItem, apiKey: String): MediaItem? {
    val tmdbItem = if (apiKey.isBlank()) null else withContext(Dispatchers.IO) {
      runCatching {
        val kind = tmdbMediaKind(item)
        val year = item.releaseYear?.take(4)?.takeIf { it.length == 4 }
        val id = resolveTmdbId(item, year, kind, apiKey)
          ?: return@withContext null
        val detail = get("https://api.themoviedb.org/3/$kind/$id?api_key=${enc(apiKey)}&append_to_response=external_ids")
        val dates = detail.string(if (kind == "movie") "release_date" else "first_air_date")
        val releaseYear = dates?.take(4) ?: item.releaseYear
        val posterPath = detail.string("poster_path")
        val backdropPath = detail.string("backdrop_path")
        val genres = detail["genres"]?.jsonArray?.mapNotNull { it.jsonObject.string("name") }.orEmpty()
        val externalIds = detail["external_ids"]?.jsonObject
        val imdbId = detail.string("imdb_id") ?: externalIds?.string("imdb_id") ?: item.imdbId
        val seasons = if (kind == "tv") {
          val seasonRefs = detail["seasons"]?.jsonArray?.mapNotNull { value ->
            val season = value.jsonObject
            val number = season["season_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            if (number <= 0) null else number
          }.orEmpty().distinct().take(30)
          coroutineScope {
            seasonRefs.map { seasonNumber -> async {
              runCatching { loadSeason(id, seasonNumber, apiKey) }.getOrNull()
            } }.awaitAll().filterNotNull().sortedBy { it.number }
          }
        } else emptyList()
        item.copy(
          title = detail.string(if (kind == "movie") "title" else "name") ?: item.title,
          overview = detail.string("overview")?.takeIf { it.isNotBlank() } ?: item.overview,
          posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/original$it" } ?: item.posterUrl,
          backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" } ?: item.backdropUrl,
          tmdbId = id,
          imdbId = imdbId,
          releaseYear = releaseYear,
          contentRating = detail["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(it) } ?: item.contentRating,
          duration = detail["runtime"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }?.let { "$it min" } ?: item.duration,
          genres = genres.ifEmpty { item.genres },
          seasons = mergeSeasons(item.seasons, seasons),
        )
      }.getOrNull()
    }
    if (item.type != MediaType.TV) return tmdbItem
    val base = tmdbItem ?: item
    // Catalog add-ons and TMDB can provide nonblank placeholders, stale URLs,
    // or generic show artwork for every season. Never use URL presence as a
    // freshness test: TVMaze's season endpoint is the authoritative source for
    // per-season artwork here. mergeSeasons preserves TMDB/add-on episodes and
    // replaces their season posters with TVMaze's original-resolution images.
    return loadKeylessSeasonArtwork(base) ?: tmdbItem ?: item
  }

  /** Resolves only the IMDb external ID, reusing cached TMDB detail/search responses when available. */
  suspend fun resolveImdbId(item: MediaItem, apiKey: String): String? {
    existingImdbId(item)?.let { return it }
    if (apiKey.isBlank()) return null
    return withContext(Dispatchers.IO) {
      runCatching {
        val kind = tmdbMediaKind(item)
        val year = item.releaseYear?.take(4)?.takeIf { it.length == 4 }
        val id = resolveTmdbId(item, year, kind, apiKey) ?: return@runCatching null
        val detail = get("https://api.themoviedb.org/3/$kind/$id?api_key=${enc(apiKey)}&append_to_response=external_ids")
        (detail.string("imdb_id") ?: detail["external_ids"]?.jsonObject?.string("imdb_id"))
          ?.takeIf { it.startsWith("tt", ignoreCase = true) }
      }.getOrNull()
    }
  }

  private fun resolveTmdbId(item: MediaItem, year: String?, kind: String, apiKey: String): Int? {
    return explicitTmdbId(item) ?: searchTmdbResult(item.title, year, kind, apiKey)
      ?.get("id")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
  }

  private fun explicitTmdbId(item: MediaItem): Int? {
    val providerId = item.providerId.orEmpty().trim()
      .replace(Regex("^(?:tmdb[:/]|movie:|series:)", RegexOption.IGNORE_CASE), "")
      .substringBefore(':')
      .substringBefore('/')
      .trim()
    return item.tmdbId?.takeIf { it > 0 }
      ?: providerId.toIntOrNull()?.takeIf { it > 0 }
  }

  private fun existingImdbId(item: MediaItem): String? =
    listOfNotNull(item.imdbId, item.providerId).map { it.trim() }
      .firstOrNull { it.substringBefore(':').substringBefore('/').startsWith("tt", ignoreCase = true) }
      ?.substringBefore(':')
      ?.substringBefore('/')

  /**
   * Nuvio ships a build-time TMDB key. Mpv-infinity intentionally does not ship
   * that credential, so keep the same enrichment contract with TVMaze as a
   * public, keyless fallback. Most importantly, this returns real per-season
   * posters instead of allowing the UI to fall through to the S1/S2 label.
   */
  private suspend fun loadKeylessSeasonArtwork(item: MediaItem): MediaItem? = withContext(Dispatchers.IO) {
    if (item.type != MediaType.TV) return@withContext null
    val result = runCatching {
      val query = URLEncoder.encode(item.title, "UTF-8")
      val byImdb = item.imdbId?.takeIf { it.startsWith("tt") }?.let { imdb ->
        runCatching { get("https://api.tvmaze.com/lookup/shows?imdb=${enc(imdb)}") }.getOrNull()
      }
      val byTvMazeId = item.providerId.orEmpty().let { providerId ->
        Regex("(?:tvmaze[:/])([0-9]+)", RegexOption.IGNORE_CASE).find(providerId)?.groupValues?.getOrNull(1)?.let { id ->
          runCatching { get("https://api.tvmaze.com/shows/$id") }.getOrNull()
        }
      }
      // Resolve one stable TVMaze show ID first. The official API recommends
      // using a direct resource after ID resolution rather than repeatedly
      // searching or depending on embedded collections.
      val direct = byImdb ?: byTvMazeId ?: runCatching { get("https://api.tvmaze.com/singlesearch/shows?q=$query") }.getOrNull()
      val detail = direct ?: run {
        val results = getArray("https://api.tvmaze.com/search/shows?q=$query")
        val expected = normalize(item.title)
        val selected = results.mapNotNull { result ->
          val show = result.jsonObject["show"]?.jsonObject ?: return@mapNotNull null
          show to scoreTvMaze(show, expected, item.releaseYear?.take(4))
        }.maxByOrNull { it.second }?.first ?: return@runCatching null
        val id = selected["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@runCatching null
        get("https://api.tvmaze.com/shows/$id")
      }
      val tvMazeShowId = detail["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
      // The TVMaze seasons page is backed by this dedicated array endpoint.
      // Use it instead of depending on _embedded.seasons being present in the
      // single-show response, and therefore fetch the exact image objects that
      // TVMaze exposes for each season.
      val seasonPayload = tvMazeShowId?.let { id ->
        runCatching { getArray("https://api.tvmaze.com/shows/$id/seasons") }.getOrNull()
      } ?: detail["_embedded"]?.jsonObject?.get("seasons")?.jsonArray.orEmpty()
      val seasons = seasonPayload.mapNotNull { value ->
        val season = value.jsonObject
        val number = season["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
        // TVMaze legitimately returns image: null for future/unartworked
        // seasons. Do not let that JsonNull abort parsing every other season.
        val image = season["image"] as? JsonObject
        val poster = image?.string("original") ?: image?.string("medium")
        Season(number = number, posterUrl = poster)
      }.filter { it.number > 0 }.sortedBy { it.number }
      if (seasons.none { !it.posterUrl.isNullOrBlank() }) return@runCatching null
      item.copy(seasons = mergeSeasons(item.seasons, seasons))
    }.onFailure { error ->
      Log.w(TAG, "TVMaze season artwork failed title=${item.title}: ${error.message.orEmpty()}")
    }.getOrNull()
    if (result == null) Log.w(TAG, "TVMaze returned no season artwork title=${item.title}")
    result
  }

  private fun searchTmdbResult(title: String, year: String?, kind: String, apiKey: String): JsonObject? {
    val query = URLEncoder.encode(title, "UTF-8")
    val yearParam = year?.let { if (kind == "movie") "&primary_release_year=$it" else "&first_air_date_year=$it" }.orEmpty()
    val search = get("https://api.themoviedb.org/3/search/$kind?api_key=${enc(apiKey)}&query=$query$yearParam")
    val expectedTitle = normalize(title)
    return search["results"]?.jsonArray.orEmpty().map { it.jsonObject }.maxWithOrNull(compareBy<JsonObject>(
      { normalize(it.string("title") ?: it.string("name").orEmpty()) == expectedTitle },
      { year != null && (it.string("release_date") ?: it.string("first_air_date")).orEmpty().startsWith(year) },
      { prefixScore(normalize(it.string("title") ?: it.string("name").orEmpty()), expectedTitle) },
    ))
  }

  private fun scoreTvMaze(show: JsonObject, expectedTitle: String, year: String?): Int {
    val name = normalize(show.string("name").orEmpty())
    val premiered = show.string("premiered").orEmpty()
    return (if (name == expectedTitle) 1000 else 0) +
      (if (year != null && premiered.startsWith(year)) 100 else 0) + prefixScore(name, expectedTitle)
  }

  private fun loadSeason(id: Int, seasonNumber: Int, apiKey: String): Season {
    val body = get("https://api.themoviedb.org/3/tv/$id/season/$seasonNumber?api_key=${enc(apiKey)}")
    val episodes = body["episodes"]?.jsonArray?.mapNotNull { element ->
      val ep = element.jsonObject
      val number = ep["episode_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
      Episode(
        number = number,
        title = ep.string("name") ?: "Episode $number",
        overview = ep.string("overview").orEmpty(),
        stillUrl = ep.string("still_path")?.let { "https://image.tmdb.org/t/p/w500$it" },
        seasonPosterUrl = body.string("poster_path")?.let { "https://image.tmdb.org/t/p/w780$it" },
        runtime = ep["runtime"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { "$it min" },
      )
    }.orEmpty().sortedBy { it.number }
    val posterUrl = body.string("poster_path")?.let { "https://image.tmdb.org/t/p/w780$it" }
    return Season(seasonNumber, episodes, posterUrl)
  }

  internal fun mergeSeasons(existing: List<Season>, enriched: List<Season>): List<Season> {
    if (enriched.isEmpty()) return existing
    val existingByNumber = existing.associateBy { it.number }
    val enrichedByNumber = enriched.associateBy { it.number }
    return (existingByNumber.keys + enrichedByNumber.keys).sorted().map { number ->
      val original = existingByNumber[number]
      val fresh = enrichedByNumber[number]
      when {
        original == null -> fresh!!
        fresh == null -> original
        else -> fresh.copy(
          episodes = if (fresh.episodes.isNotEmpty()) {
            val originalEpisodes = original.episodes.associateBy { it.number }
            fresh.episodes.map { episode ->
              episode.copy(videoId = episode.videoId ?: originalEpisodes[episode.number]?.videoId)
            }
          } else original.episodes,
          posterUrl = fresh.posterUrl ?: original.posterUrl,
        )
      }
    }
  }

  private data class CachedResponse(val expiresAt: Long, val body: JsonElement)

  private fun get(url: String): JsonObject = getJson(url).jsonObject

  private fun getArray(url: String): JsonArray = getJson(url).jsonArray

  private fun getJson(url: String): JsonElement {
    val now = System.currentTimeMillis()
    responseCache[url]?.takeIf { it.expiresAt > now }?.let { return it.body }
    var retry = 0
    while (true) {
    val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Mpv-infinity Nuvio metadata").build()
    http.newCall(request).execute().use { response ->
      if (response.code == 429 && retry < 3) {
        Thread.sleep(1_000L shl retry)
        retry++
      } else {
        if (!response.isSuccessful) error("Metadata returned HTTP ${response.code}")
        val body = json.parseToJsonElement(response.body.string())
        responseCache[url] = CachedResponse(System.currentTimeMillis() + CACHE_TTL_MS, body)
        return body
      }
    }
    }
  }

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
  private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
  private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
  private fun prefixScore(a: String, b: String): Int = a.zip(b).takeWhile { it.first == it.second }.size

  private companion object {
    const val TAG = "MpvCatalogDiag"
    const val CACHE_TTL_MS = 60 * 60 * 1_000L
  }
}
