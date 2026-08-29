package app.infinity.mpvz.ui.browser.jellyfin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder

internal class SeerrClient(private val httpClient: OkHttpClient) {
  private val json = Json { ignoreUnknownKeys = true }
  private var sessionCookie: String? = null
  private var apiKey: String? = null

  fun setApiKey(value: String?) {
    apiKey = value?.trim()?.takeIf { it.isNotEmpty() }
  }

  suspend fun loginLocal(baseUrl: String, email: String, password: String): Result<String> =
    postAuth(baseUrl, "/auth/local", buildJsonObject {
      put("email", email)
      put("password", password)
    })

  // Matches mpvRx: Seerr resolves the Jellyfin server configured on Seerr itself.
  // Do not send hostname/serverType; those fields make otherwise valid logins fail on many installs.
  suspend fun loginJellyfin(baseUrl: String, username: String, password: String): Result<String> =
    postAuth(baseUrl, "/auth/jellyfin", buildJsonObject {
      put("username", username)
      put("password", password)
    })

  suspend fun verifyApiKey(baseUrl: String): Result<String> = request(baseUrl, "/auth/me")
    .map { parseUserName(it) ?: "Seerr" }

  suspend fun discover(baseUrl: String): Result<SeerrDiscoverState> {
    return runCatching {
      val movies = parseResults(request(baseUrl, "/discover/movies?sortBy=popularity.desc&page=1&language=en").getOrThrow(), "movie")
      val shows = parseResults(request(baseUrl, "/discover/tv?sortBy=popularity.desc&page=1&language=en").getOrThrow(), "tv")
      val trending = parseResults(request(baseUrl, "/discover/trending?page=1").getOrThrow(), null)
      SeerrDiscoverState(
        isConnected = true,
        userName = request(baseUrl, "/auth/me").getOrNull()?.let(::parseUserName),
        movies = movies,
        shows = shows,
        trending = trending,
      )
    }
  }

  suspend fun search(baseUrl: String, query: String): Result<List<SeerrMediaItem>> {
    val encoded = URLEncoder.encode(query.trim(), "UTF-8")
    return request(baseUrl, "/search?query=$encoded&page=1").map { parseResults(it, null) }
  }

  suspend fun getDetails(baseUrl: String, media: SeerrMediaItem): Result<SeerrMediaItem> = request(
    baseUrl,
    "/${if (media.mediaType == "tv") "tv" else "movie"}/${media.id}",
  ).map { raw ->
    val root = json.parseToJsonElement(raw).jsonObject
    val obj = root["mediaInfo"]?.jsonObject ?: root
    val credits = root["credits"]?.jsonObject ?: obj["credits"]?.jsonObject
    val cast = credits?.get("cast")?.jsonArray.orEmpty().mapNotNull { element ->
      val person = element.jsonObject
      val name = person["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
      SeerrCastMember(name, person["character"]?.jsonPrimitive?.contentOrNull, person["profilePath"]?.jsonPrimitive?.contentOrNull)
    }.take(12)
    val genres = obj["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
    val mediaInfo = root["mediaInfo"]?.jsonObject
    val jellyfinMediaId = mediaInfo?.get("jellyfinMediaId")?.jsonPrimitive?.contentOrNull
    val seasons = obj["seasons"]?.jsonArray.orEmpty().mapNotNull { element ->
      val season = element.jsonObject
      val number = season["seasonNumber"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val status = season["status"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
      SeerrSeason(
        seasonNumber = number,
        name = season["name"]?.jsonPrimitive?.contentOrNull ?: "Season $number",
        episodeCount = season["episodeCount"]?.jsonPrimitive?.intOrNull ?: 0,
        available = season["available"]?.jsonPrimitive?.booleanOrNull == true || status == "available",
        requested = season["requested"]?.jsonPrimitive?.booleanOrNull == true || status.contains("requested") || status.contains("processing"),
      )
    }
    val mediaStatus = mediaInfo?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty()
    val requestRecords = root["requests"]?.jsonArray ?: mediaInfo?.get("requests")?.jsonArray
    val hasRequest = requestRecords?.isNotEmpty() == true || mediaStatus.contains("requested", true) || mediaStatus.contains("processing", true)
    val has4kRequest = requestRecords?.any { it.jsonObject["is4k"]?.jsonPrimitive?.booleanOrNull == true } == true
    val isTv = media.mediaType.equals("tv", true)
    val allSeasonsAvailable = seasons.isNotEmpty() && seasons.all { it.available }
    val someSeasonsAvailable = seasons.any { it.available }
    val available = if (isTv && seasons.isNotEmpty()) allSeasonsAvailable else mediaStatus.equals("available", true) || !jellyfinMediaId.isNullOrBlank()
    val partialStatus = mediaStatus.contains("partial", true) || mediaStatus.contains("partially", true) || (isTv && someSeasonsAvailable && !allSeasonsAvailable)
    media.copy(
      overview = obj["overview"]?.jsonPrimitive?.contentOrNull ?: media.overview,
      posterPath = obj["posterPath"]?.jsonPrimitive?.contentOrNull ?: media.posterPath,
      backdropPath = obj["backdropPath"]?.jsonPrimitive?.contentOrNull ?: media.backdropPath,
      releaseDate = obj["releaseDate"]?.jsonPrimitive?.contentOrNull ?: obj["firstAirDate"]?.jsonPrimitive?.contentOrNull ?: media.releaseDate,
      voteAverage = obj["voteAverage"]?.jsonPrimitive?.doubleOrNull ?: media.voteAverage,
      genres = genres.ifEmpty { media.genres },
      cast = cast,
      seasons = seasons.ifEmpty { media.seasons },
      availableInJellyfin = available || media.availableInJellyfin,
      requested = hasRequest || media.requested,
      requested4k = has4kRequest || media.requested4k,
      partiallyAvailable = partialStatus || (!available && seasons.any { it.available }) || (seasons.any { it.available } && seasons.any { !it.available }),
      jellyfinMediaId = jellyfinMediaId ?: media.jellyfinMediaId,
    )
  }

  suspend fun requestMedia(
    baseUrl: String,
    media: SeerrMediaItem,
    is4k: Boolean = false,
    seasons: List<Int>? = null,
    audioPreference: SeerrAudioPreference = SeerrAudioPreference.DEFAULT,
  ): Result<Unit> = runCatching {
    val body = buildJsonObject {
      put("mediaType", if (media.mediaType == "tv") "tv" else "movie")
      put("mediaId", media.id)
      put("is4k", is4k)
      seasons?.takeIf { it.isNotEmpty() }?.let { selected -> put("seasons", buildJsonArray { selected.forEach { add(JsonPrimitive(it)) } }) }
      if (audioPreference != SeerrAudioPreference.DEFAULT) put("audioPreference", audioPreference.name.lowercase())
    }
    request(baseUrl, "/request", "POST", body).getOrElse { error ->
      if (is4k && (error.message?.contains("403") == true || error.message?.contains("permission", true) == true || error.message?.contains("forbidden", true) == true)) {
        throw IOException("Seerr denied this 4K request. Enable 4K request permission for this account in Seerr, then try again.")
      }
      throw error
    }
    Unit
  }

  private suspend fun postAuth(baseUrl: String, path: String, body: JsonObject): Result<String> =
    request(baseUrl, path, "POST", body).map { response -> parseUserName(response) ?: "Seerr" }

  private fun parseUserName(raw: String): String? = runCatching {
    val obj = json.parseToJsonElement(raw).jsonObject
    obj["username"]?.jsonPrimitive?.contentOrNull
      ?: obj["email"]?.jsonPrimitive?.contentOrNull
  }.getOrNull()

  private fun parseResults(raw: String, forcedType: String?): List<SeerrMediaItem> = runCatching {
    val root = json.parseToJsonElement(raw).jsonObject
    root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
      val item = element.jsonObject
      val id = item["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val type = forcedType ?: item["mediaType"]?.jsonPrimitive?.contentOrNull ?: "movie"
      val title = item["title"]?.jsonPrimitive?.contentOrNull
        ?: item["name"]?.jsonPrimitive?.contentOrNull
        ?: return@mapNotNull null
      SeerrMediaItem(
        id = id,
        title = title,
        mediaType = type,
        posterPath = item["posterPath"]?.jsonPrimitive?.contentOrNull,
        backdropPath = item["backdropPath"]?.jsonPrimitive?.contentOrNull,
        overview = item["overview"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        releaseDate = item["releaseDate"]?.jsonPrimitive?.contentOrNull
          ?: item["firstAirDate"]?.jsonPrimitive?.contentOrNull,
        voteAverage = item["voteAverage"]?.jsonPrimitive?.doubleOrNull,
      )
    }
  }.getOrDefault(emptyList())

  private suspend fun request(
    baseUrl: String,
    path: String,
    method: String = "GET",
    body: JsonObject? = null,
  ): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val normalized = normalizeUrl(baseUrl)
      val url = "$normalized/api/v1$path"
      val requestBuilder = Request.Builder().url(url)
        .header("Accept", "application/json")
      apiKey?.let { requestBuilder.header("X-Api-Key", it) }
      sessionCookie?.let { requestBuilder.header("Cookie", it) }
      if (method == "POST") {
        val requestBody = (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        requestBuilder.post(requestBody)
      }
      httpClient.newCall(requestBuilder.build()).execute().use { response ->
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
          val detail = runCatching {
            json.parseToJsonElement(responseBody).jsonObject["message"]?.jsonPrimitive?.contentOrNull
          }.getOrNull()
          error(detail ?: "Seerr returned HTTP ${response.code}")
        }
        response.headers("Set-Cookie").firstOrNull()?.let { cookie ->
          sessionCookie = cookie.substringBefore(';')
        }
        responseBody
      }
    }
  }

  companion object {
    fun generateCandidateUrls(input: String): List<String> {
      val trimmed = input.trim().removeSuffix("/")
      if (trimmed.isBlank()) return emptyList()
      val hasScheme = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
      val parsed = runCatching { URI(if (hasScheme) trimmed else "http://$trimmed") }.getOrNull()
      val host = parsed?.host?.takeIf { it.isNotBlank() } ?: trimmed
      val port = parsed?.port ?: -1
      return when {
        hasScheme && port != -1 -> listOf(trimmed)
        !hasScheme && port != -1 -> listOf("https://$trimmed", "http://$trimmed")
        hasScheme && parsed?.scheme.equals("https", true) -> listOf(trimmed, "https://$host:5055")
        hasScheme && parsed?.scheme.equals("http", true) -> listOf(trimmed, "http://$host:5055")
        else -> listOf("https://$host", "https://$host:5055", "http://$host:5055", "http://$host")
      }
    }

    fun normalizeUrl(value: String): String = generateCandidateUrls(value).firstOrNull().orEmpty()
  }
}
