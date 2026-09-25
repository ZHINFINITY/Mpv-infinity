package app.infinity.mpvz.catalog.nuvio

import app.infinity.mpvz.catalog.Episode
import app.infinity.mpvz.catalog.MediaItem
import app.infinity.mpvz.catalog.MediaType
import app.infinity.mpvz.catalog.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** TMDB-backed details/episodes path used by NuvioMobile. Falls back to catalog metadata when no user API key is configured. */
internal class TmdbMetadataRepository {
  private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun load(item: MediaItem, apiKey: String): MediaItem? {
    if (apiKey.isBlank()) return null
    return withContext(Dispatchers.IO) {
      runCatching {
        val kind = if (item.type == MediaType.MOVIE) "movie" else "tv"
        val query = URLEncoder.encode(item.title, "UTF-8")
        val year = item.releaseYear?.take(4)?.takeIf { it.length == 4 }
        val yearParam = year?.let { if (kind == "movie") "&primary_release_year=$it" else "&first_air_date_year=$it" }.orEmpty()
        val search = get("https://api.themoviedb.org/3/search/$kind?api_key=${enc(apiKey)}&query=$query$yearParam")
        val results = search["results"]?.jsonArray ?: JsonArray(emptyList())
        val expectedTitle = normalize(item.title)
        val matches = results.map { it.jsonObject }
        val selected = matches.maxWithOrNull(compareBy<JsonObject>(
          { normalize(it.string("title") ?: it.string("name").orEmpty()) == expectedTitle },
          { year != null && (it.string("release_date") ?: it.string("first_air_date")).orEmpty().startsWith(year) },
          { prefixScore(normalize(it.string("title") ?: it.string("name").orEmpty()), expectedTitle) },
        )) ?: return@withContext null
        val id = selected["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@withContext null
        val detail = get("https://api.themoviedb.org/3/$kind/$id?api_key=${enc(apiKey)}&append_to_response=external_ids")
        val dates = detail.string(if (kind == "movie") "release_date" else "first_air_date") ?: selected.string(if (kind == "movie") "release_date" else "first_air_date")
        val releaseYear = dates?.take(4) ?: item.releaseYear
        val posterPath = detail.string("poster_path") ?: selected.string("poster_path")
        val backdropPath = detail.string("backdrop_path") ?: selected.string("backdrop_path")
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
          posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: item.posterUrl,
          backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" } ?: item.backdropUrl,
          tmdbId = id,
          imdbId = imdbId,
          releaseYear = releaseYear,
          contentRating = detail["vote_average"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(it) } ?: item.contentRating,
          duration = detail["runtime"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }?.let { "$it min" } ?: item.duration,
          genres = genres.ifEmpty { item.genres },
          seasons = seasons.ifEmpty { item.seasons },
        )
      }.getOrNull()
    }
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
        runtime = ep["runtime"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { "$it min" },
      )
    }.orEmpty().sortedBy { it.number }
    val posterUrl = body.string("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" }
    return Season(seasonNumber, episodes, posterUrl)
  }

  private fun get(url: String): JsonObject {
    val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Mpv-infinity Nuvio metadata").build()
    http.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("TMDB returned HTTP ${response.code}")
      return json.parseToJsonElement(response.body.string()).jsonObject
    }
  }

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
  private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
  private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
  private fun prefixScore(a: String, b: String): Int = a.zip(b).takeWhile { it.first == it.second }.size
}
