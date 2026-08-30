/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.content.Context
import android.net.Uri
import android.os.Build
import app.infinity.mpvz.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

class JellyfinClient(
  private val httpClient: OkHttpClient,
  private val context: Context,
) {
  private val json = Json { ignoreUnknownKeys = true }

  companion object {
    fun normalizeUrl(raw: String): String = normalizeUrlCandidates(raw).first()

    fun normalizeUrlCandidates(raw: String): List<String> {
      val trimmed = raw.trim().removeSuffix("/")
      require(trimmed.isNotBlank()) { "Enter a Jellyfin server URL" }
      if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        require(!Uri.parse(trimmed).host.isNullOrBlank()) {
          "Enter a valid Jellyfin server address, for example jellyfin.example.com"
        }
        return listOf(trimmed)
      }
      val clean = trimmed.removePrefix("//")
      val host = clean.substringBefore('/').substringBeforeLast(':')
      val port = clean.substringAfterLast(":", "").substringBefore('/').toIntOrNull()
      val local = host.equals("localhost", ignoreCase = true) ||
        host == "127.0.0.1" ||
        host.startsWith("192.168.") ||
        host.startsWith("10.") ||
        (host.startsWith("172.") && host.substringAfter("172.").substringBefore('.').toIntOrNull() in 16..31) ||
        host.endsWith(".local", ignoreCase = true) ||
        host.endsWith(".lan", ignoreCase = true)
      val candidates = if (local || port == 80 || port == 8096) {
        listOf("http://$clean", "https://$clean")
      } else {
        listOf("https://$clean", "http://$clean")
      }
      require(!Uri.parse(candidates.first()).host.isNullOrBlank()) {
        "Enter a valid Jellyfin server address, for example jellyfin.example.com"
      }
      return candidates
    }
  }

  fun getStreamUrl(session: JellyfinSession, itemId: String, isVideo: Boolean = true): String {
    val base = normalizeUrl(session.serverUrl)
    val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
    val mediaPath = if (isVideo) "Videos" else "Audio"
    return "$base/$mediaPath/$itemId/stream?static=true&api_key=$encodedToken"
  }

  fun getImageUrl(session: JellyfinSession, itemId: String, imageTag: String? = null, maxWidth: Int = 600): String {
    val base = normalizeUrl(session.serverUrl)
    val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
    val tagParam = if (!imageTag.isNullOrBlank()) "&tag=$imageTag" else ""
    return "$base/Items/$itemId/Images/Primary?maxWidth=$maxWidth&quality=90$tagParam&api_key=$encodedToken"
  }

  suspend fun authenticate(
    rawServerUrl: String,
    username: String,
    password: String,
  ): Result<JellyfinSession> = withContext(Dispatchers.IO) {
    val candidates = normalizeUrlCandidates(rawServerUrl)
    var lastError: Throwable = IOException("Unable to reach Jellyfin server")
    for (serverUrl in candidates) {
      try {
        val body = "{\"Username\":${jsonString(username)},\"Pw\":${jsonString(password)}}"
        val request = Request.Builder()
          .url("$serverUrl/Users/AuthenticateByName")
          .addJellyfinHeaders()
          .header("Content-Type", "application/json")
          .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
          .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Jellyfin login failed: HTTP ${response.code}")
          }
          val root = json.parseToJsonElement(response.body.string()).jsonObject
          val token = root["AccessToken"]?.jsonPrimitive?.content
            ?: throw IOException("Jellyfin did not return an access token")
          val userId = root["User"]?.jsonObject?.get("Id")?.jsonPrimitive?.content
            ?: throw IOException("Jellyfin did not return a user id")
          return@withContext Result.success(
            JellyfinSession(serverUrl = serverUrl, userId = userId, accessToken = token),
          )
        }
      } catch (error: Throwable) {
        lastError = error
      }
    }
    Result.failure(lastError)
  }

  suspend fun authenticateWithToken(serverUrl: String, token: String): Result<JellyfinSession> =
    withContext(Dispatchers.IO) {
      runCatching {
        require(token.isNotBlank()) { "Enter a Jellyfin API token" }
        val candidate = normalizeUrlCandidates(serverUrl).first()
        val request = Request.Builder()
          .url("$candidate/Users/Me")
          .addJellyfinHeaders(token.trim())
          .get()
          .build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Jellyfin token login failed: HTTP ${response.code}")
          val user = json.parseToJsonElement(response.body.string()).jsonObject
          val userId = user["Id"]?.jsonPrimitive?.content ?: throw IOException("Jellyfin response did not include a user ID")
          JellyfinSession(candidate, userId, token.trim())
        }
      }
    }

  suspend fun loadLibraries(session: JellyfinSession): Result<List<JellyfinCollection>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
        val url = "${session.serverUrl}/Users/${session.userId}/Views?api_key=$encodedToken"
        val request = Request.Builder()
          .url(url)
          .addJellyfinHeaders(session.accessToken)
          .get()
          .build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Failed to load Jellyfin libraries: HTTP ${response.code}")
          val root = json.parseToJsonElement(response.body.string()).jsonObject
          val items = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          items.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["Id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = obj["Name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val collectionType = obj["CollectionType"]?.jsonPrimitive?.contentOrNull
            val imageTag = obj["ImageTags"]?.jsonObject?.get("Primary")?.jsonPrimitive?.contentOrNull
            val artworkUrl = getImageUrl(session, id, imageTag, maxWidth = 900)
            JellyfinCollection(
              id = id,
              name = name,
              collectionType = collectionType,
              artworkUrl = artworkUrl,
            )
          }
        }
      }
    }

  suspend fun loadMedia(
    session: JellyfinSession,
    parentId: String,
    limit: Int = 50,
    startIndex: Int = 0,
    sortBy: String = "DateCreated",
    sortOrder: String = "Descending",
    includeItemTypes: String? = null,
    libraryName: String? = null,
  ): Result<List<JellyfinTrack>> = withContext(Dispatchers.IO) {
    runCatching {
      val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
      val typeParam = includeItemTypes?.takeIf { it.isNotBlank() }?.let { "&IncludeItemTypes=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty()
      val url = "${session.serverUrl}/Users/${session.userId}/Items" +
        "?ParentId=$parentId&Limit=$limit&StartIndex=$startIndex" +
        "" + typeParam +
        "&SortBy=$sortBy&SortOrder=$sortOrder&Recursive=true" +
        "&Fields=Overview,RunTimeTicks,ImageTags,MediaStreams,ProductionYear,CommunityRating,CriticRating,Genres,Studios,RemoteTrailers,ProviderIds" +
        "&api_key=$encodedToken"
      val request = Request.Builder()
        .url(url)
        .addJellyfinHeaders(session.accessToken)
        .get()
        .build()
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Failed to load Jellyfin media: HTTP ${response.code}")
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val items = root["Items"]?.jsonArray ?: JsonArray(emptyList())
        items.mapNotNull { parseTrack(it.jsonObject, session, libraryName) }
      }
    }
  }

  suspend fun loadAllMedia(
    session: JellyfinSession,
    parentId: String,
    sortBy: String = "DateCreated",
    sortOrder: String = "Descending",
    includeItemTypes: String? = null,
    libraryName: String? = null,
  ): Result<List<JellyfinTrack>> = withContext(Dispatchers.IO) {
    runCatching {
      val pageSize = 100
      buildList {
        var startIndex = 0
        while (true) {
          val page = loadMedia(
            session = session,
            parentId = parentId,
            limit = pageSize,
            startIndex = startIndex,
            sortBy = sortBy,
            sortOrder = sortOrder,
            includeItemTypes = includeItemTypes,
            libraryName = libraryName,
          ).getOrThrow()
          addAll(page)
          if (page.size < pageSize) break
          startIndex += page.size
        }
      }
    }
  }

  suspend fun loadSimilarItems(session: JellyfinSession, itemId: String, limit: Int = 12): Result<List<JellyfinTrack>> = withContext(Dispatchers.IO) {
    runCatching {
      val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
      val url = "${session.serverUrl}/Items/$itemId/Similar" +
        "?UserId=${session.userId}&Limit=$limit" +
        "&Fields=Overview,RunTimeTicks,ImageTags,MediaStreams,ProductionYear,CommunityRating,CriticRating,Genres,Studios,RemoteTrailers,ProviderIds" +
        "&api_key=$encodedToken"
      val request = Request.Builder().url(url).addJellyfinHeaders(session.accessToken).get().build()
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Failed to load related Jellyfin items: HTTP ${response.code}")
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val items = root["Items"]?.jsonArray ?: JsonArray(emptyList())
        items.mapNotNull { parseTrack(it.jsonObject, session) }
      }
    }
  }

  suspend fun loadItem(session: JellyfinSession, itemId: String): Result<JellyfinTrack> = withContext(Dispatchers.IO) {
    runCatching {
      val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
      val url = "${session.serverUrl}/Users/${session.userId}/Items/$itemId" +
        "?Fields=Overview,RunTimeTicks,ImageTags,MediaStreams,ProductionYear,PremiereDate,EndDate,OriginalTitle,OfficialRating,Status,Genres,Studios,RemoteTrailers,ProviderIds,Chapters,UserData" +
        "&api_key=$encodedToken"
      val request = Request.Builder().url(url).addJellyfinHeaders(session.accessToken).get().build()
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Failed to load Jellyfin item: HTTP ${response.code}")
        parseTrack(json.parseToJsonElement(response.body.string()).jsonObject, session)
          ?: throw IOException("Jellyfin returned an empty item")
      }
    }
  }

  suspend fun loadWatchHistory(
    session: JellyfinSession,
    limit: Int = 25,
  ): Result<List<JellyfinTrack>> = withContext(Dispatchers.IO) {
    runCatching {
      val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
      val fields = "Overview,RunTimeTicks,ImageTags,MediaStreams,ProductionYear,PremiereDate,EndDate,OriginalTitle,OfficialRating,Status,Genres,Studios,RemoteTrailers,ProviderIds,Chapters,UserData"
      fun query(filter: String): List<JellyfinTrack> {
        val url = "${session.serverUrl}/Users/${session.userId}/Items" +
          "?Filters=$filter&IncludeItemTypes=Movie,Series,Episode&EnableUserData=true" +
          "&SortBy=DatePlayed&SortOrder=Descending&Limit=$limit&Recursive=true" +
          "&Fields=$fields&api_key=$encodedToken"
        val request = Request.Builder().url(url).addJellyfinHeaders(session.accessToken).get().build()
        return httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Failed to load Jellyfin watch history: HTTP ${response.code}")
          val root = json.parseToJsonElement(response.body.string()).jsonObject
          val items = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          items.mapNotNull { parseTrack(it.jsonObject, session) }
        }
      }
      (runCatching { query("IsResumable") }.getOrDefault(emptyList()) +
        runCatching { query("IsPlayed") }.getOrDefault(emptyList()))
        .groupBy { it.id }
        .values
        .mapNotNull { records ->
          records.maxWithOrNull(compareBy<JellyfinTrack> { it.lastPlayedDate.orEmpty() }.thenBy { it.playbackPositionMs })
        }
        .sortedByDescending { it.lastPlayedDate.orEmpty() }
        .take(limit)
    }
  }

  suspend fun search(
    session: JellyfinSession,
    query: String,
    limit: Int = 50,
  ): Result<List<JellyfinTrack>> = withContext(Dispatchers.IO) {
    runCatching {
      val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
      val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
      val url = "${session.serverUrl}/Search/Hints" +
        "?SearchTerm=$encodedQuery&Limit=$limit" +
        "&IncludePeople=false&IncludeMedia=true&IncludeGenres=true&IncludeStudios=true" +
        "&api_key=$encodedToken"
      val request = Request.Builder()
        .url(url)
        .addJellyfinHeaders(session.accessToken)
        .get()
        .build()
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Search failed: HTTP ${response.code}")
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        val items = root["SearchHints"]?.jsonArray ?: JsonArray(emptyList())
        items.mapNotNull { element ->
          val obj = element.jsonObject
          val id = obj["Id"]?.jsonPrimitive?.content ?: return@mapNotNull null
          val name = obj["Name"]?.jsonPrimitive?.content ?: return@mapNotNull null
          val type = obj["Type"]?.jsonPrimitive?.content ?: "Audio"
          val seriesName = obj["SeriesName"]?.jsonPrimitive?.contentOrNull
          val originalTitle = obj["OriginalTitle"]?.jsonPrimitive?.contentOrNull
          val premiereDate = obj["PremiereDate"]?.jsonPrimitive?.contentOrNull
          val endDate = obj["EndDate"]?.jsonPrimitive?.contentOrNull
          val officialRating = obj["OfficialRating"]?.jsonPrimitive?.contentOrNull
          val status = obj["Status"]?.jsonPrimitive?.contentOrNull
          val chapterCount = obj["Chapters"]?.jsonArray?.size ?: 0
          val artist = obj["Artist"]?.jsonPrimitive?.contentOrNull ?: seriesName ?: "Unknown"
          val album = obj["Album"]?.jsonPrimitive?.contentOrNull ?: type
          val ticks = obj["RunTimeTicks"]?.jsonPrimitive?.longOrNull ?: 0L
          val tag = obj["ImageTags"]?.jsonObject?.get("Primary")?.jsonPrimitive?.content
          val artwork = if (!tag.isNullOrBlank()) {
            "${session.serverUrl}/Items/$id/Images/Primary?maxWidth=600&quality=90&tag=$tag&api_key=$encodedToken"
          } else {
            "${session.serverUrl}/Items/$id/Images/Primary?maxWidth=600&quality=90&api_key=$encodedToken"
          }
          val stream = when {
            type.equals("Audio", ignoreCase = true) ->
              "${session.serverUrl}/Audio/$id/stream?static=true&api_key=$encodedToken"
            type.equals("Movie", ignoreCase = true) || type.equals("Episode", ignoreCase = true) ->
              "${session.serverUrl}/Videos/$id/stream?static=true&api_key=$encodedToken"
            else -> null
          }
          JellyfinTrack(
            id = id,
            title = name,
            artist = artist,
            album = album,
            durationMs = ticks / 10_000L,
            artworkUrl = artwork,
            streamUrl = stream,
            mediaType = type,
            originalTitle = originalTitle,
            premiereDate = premiereDate,
            endDate = endDate,
            officialRating = officialRating,
            status = status,
            chapterCount = chapterCount,
          )
        }
      }
    }
  }

  private fun parseTrack(obj: JsonObject, session: JellyfinSession, libraryName: String? = null): JellyfinTrack? {
    val id = obj["Id"]?.jsonPrimitive?.content ?: return null
    val parentId = obj["ParentId"]?.jsonPrimitive?.contentOrNull
    val name = obj["Name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
    val mediaType = obj["Type"]?.jsonPrimitive?.content ?: "Audio"
    val artist = obj["AlbumArtist"]?.jsonPrimitive?.content
      ?: obj["Artists"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
      ?: obj["SeriesName"]?.jsonPrimitive?.content
      ?: if (mediaType.equals("Audio", ignoreCase = true)) "Unknown artist" else "Jellyfin"
    val album = obj["Album"]?.jsonPrimitive?.content
      ?: obj["SeriesName"]?.jsonPrimitive?.content
      ?: mediaType
    val ticks = obj["RunTimeTicks"]?.jsonPrimitive?.longOrNull ?: 0L
    val tag = obj["ImageTags"]?.jsonObject?.get("Primary")?.jsonPrimitive?.content
    val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
    val artwork = getImageUrl(session, id, tag, maxWidth = 600)
    val stream =
      when {
        mediaType.equals("Audio", ignoreCase = true) ->
          "${session.serverUrl}/Audio/$id/stream?static=true&api_key=$encodedToken"
        mediaType.equals("Movie", ignoreCase = true) || mediaType.equals("Episode", ignoreCase = true) ->
          "${session.serverUrl}/Videos/$id/stream?static=true&api_key=$encodedToken"
        else -> null
      }
    val overview = obj["Overview"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val originalTitle = obj["OriginalTitle"]?.jsonPrimitive?.contentOrNull
    val year = obj["ProductionYear"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    val premiereDate = obj["PremiereDate"]?.jsonPrimitive?.contentOrNull
    val endDate = obj["EndDate"]?.jsonPrimitive?.contentOrNull
    val rating = obj["CommunityRating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    val criticRating = obj["CriticRating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    val providerIds = obj["ProviderIds"]?.jsonObject
      ?.mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }
      ?.toMap()
      .orEmpty()
    val officialRating = obj["OfficialRating"]?.jsonPrimitive?.contentOrNull
    val status = obj["Status"]?.jsonPrimitive?.contentOrNull
    val seasonNumber = if (mediaType.equals("Season", ignoreCase = true)) {
      obj["IndexNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    } else {
      obj["ParentIndexNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }
    val episodeNumber = obj["IndexNumber"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    val userData = obj["UserData"]?.jsonObject
    val playbackPositionMs = (userData?.get("PlaybackPositionTicks")?.jsonPrimitive?.longOrNull ?: 0L) / 10_000L
    val lastPlayedDate = userData?.get("LastPlayedDate")?.jsonPrimitive?.contentOrNull
    val chapterCount = obj["Chapters"]?.jsonArray?.size ?: 0
    val genres = obj["Genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    val videoStream = obj["MediaStreams"]?.jsonArray?.firstOrNull {
      it.jsonObject["Type"]?.jsonPrimitive?.contentOrNull.equals("Video", ignoreCase = true)
    }?.jsonObject
    val width = videoStream?.get("Width")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    val height = videoStream?.get("Height")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    val range = videoStream?.get("VideoRange")?.jsonPrimitive?.contentOrNull.orEmpty()
    val quality = if (mediaType.equals("Movie", ignoreCase = true) ||
      mediaType.equals("Episode", ignoreCase = true) ||
      mediaType.equals("Series", ignoreCase = true) ||
      mediaType.equals("Season", ignoreCase = true)
    ) {
      buildString {
        // Jellyfin commonly omits MediaStreams for Series/Season containers;
        // keep the chip visible rather than dropping it from those posters.
        if (videoStream == null && (mediaType.equals("Series", ignoreCase = true) || mediaType.equals("Season", ignoreCase = true))) {
          append("HD")
        } else {
          append(when {
            height >= 2160 || width >= 3800 -> "4K"
            height >= 1440 || width >= 2500 -> "1440p"
            height >= 1080 || width >= 1900 -> "1080p"
            height >= 720 || width >= 1200 -> "720p"
            else -> "SD"
          })
          if (range.contains("HDR", ignoreCase = true)) append(" HDR")
        }
      }
    } else null
    val trailer = obj["RemoteTrailers"]?.jsonArray?.firstOrNull()?.jsonObject?.get("Url")?.jsonPrimitive?.contentOrNull
    val studio = obj["Studios"]?.jsonArray?.firstOrNull()?.jsonObject?.get("Name")?.jsonPrimitive?.contentOrNull
    return JellyfinTrack(
      id = id,
      parentId = parentId,
      title = name,
      artist = artist,
      album = album,
      durationMs = ticks / 10_000L,
      artworkUrl = artwork,
      streamUrl = stream,
      mediaType = mediaType,
      libraryName = libraryName,
      overview = overview,
      originalTitle = originalTitle,
      productionYear = year,
      premiereDate = premiereDate,
      endDate = endDate,
      officialRating = officialRating,
      status = status,
      seasonNumber = seasonNumber,
      episodeNumber = episodeNumber,
      chapterCount = chapterCount,
      communityRating = rating,
      criticRating = criticRating,
      providerIds = providerIds,
      genres = genres,
      qualityLabel = quality,
      trailerUrl = trailer,
      studio = studio,
      playbackPositionMs = playbackPositionMs,
      lastPlayedDate = lastPlayedDate,
    )
  }

  private fun authHeader(token: String? = null): String {
    val model = Build.MODEL.orEmpty()
    val manufacturer = Build.MANUFACTURER.orEmpty()
    val device = if (model.startsWith(manufacturer, ignoreCase = true)) {
      model
    } else {
      "$manufacturer $model".trim().ifBlank { "Android" }
    }
    val base = "MediaBrowser Client=\"mpvRx\", Device=\"$device\", DeviceId=\"${getDeviceId()}\", Version=\"${BuildConfig.VERSION_NAME.ifBlank { "1.0.3-debug" }}\""
    return if (!token.isNullOrBlank()) "$base, Token=\"$token\"" else base
  }

  private fun Request.Builder.addJellyfinHeaders(token: String? = null): Request.Builder {
    val auth = authHeader(token)
    header("X-Emby-Authorization", auth)
    header("Authorization", auth)
    header("Accept", "application/json")
    header("User-Agent", "mpvRx/${BuildConfig.VERSION_NAME.ifBlank { "1.0.3-debug" }}")
    if (!token.isNullOrBlank()) {
      header("X-Emby-Token", token)
      header("X-MediaBrowser-Token", token)
    }
    return this
  }

  private fun getDeviceId(): String {
    val prefs = context.getSharedPreferences("jellyfin_client_prefs", Context.MODE_PRIVATE)
    val existing = prefs.getString("device_id", null)
    if (!existing.isNullOrBlank()) return existing
    val created = UUID.randomUUID().toString().replace("-", "")
    prefs.edit().putString("device_id", created).apply()
    return created
  }

  private fun jsonString(value: String): String =
    Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(value))
}
