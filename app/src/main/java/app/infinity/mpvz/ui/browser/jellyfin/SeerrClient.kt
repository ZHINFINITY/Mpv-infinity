package app.infinity.mpvz.ui.browser.jellyfin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

  suspend fun loginJellyfin(baseUrl: String, username: String, password: String, hostname: String): Result<String> =
    postAuth(baseUrl, "/auth/jellyfin", buildJsonObject {
      put("username", username)
      put("password", password)
      put("hostname", hostname)
      put("serverType", 3)
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

  suspend fun requestMedia(baseUrl: String, media: SeerrMediaItem): Result<Unit> = runCatching {
    val body = buildJsonObject {
      put("mediaType", if (media.mediaType == "tv") "tv" else "movie")
      put("mediaId", media.id)
    }
    request(baseUrl, "/request", "POST", body).getOrThrow()
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

  private fun request(
    baseUrl: String,
    path: String,
    method: String = "GET",
    body: JsonObject? = null,
  ): Result<String> = runCatching {
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
      if (!response.isSuccessful) error("Seerr returned HTTP ${response.code}")
      response.headers("Set-Cookie").firstOrNull()?.let { cookie ->
        sessionCookie = cookie.substringBefore(';')
      }
      response.body?.string().orEmpty()
    }
  }

  companion object {
    fun normalizeUrl(value: String): String {
      val trimmed = value.trim().removeSuffix("/")
      return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
    }
  }
}
