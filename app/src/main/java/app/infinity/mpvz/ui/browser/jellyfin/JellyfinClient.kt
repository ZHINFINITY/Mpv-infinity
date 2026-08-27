/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.browser.jellyfin

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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

internal class JellyfinClient(
  private val httpClient: OkHttpClient,
  private val context: Context,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun authenticate(
    rawServerUrl: String,
    username: String,
    password: String,
  ): Result<JellyfinSession> = withContext(Dispatchers.IO) {
    runCatching {
      val serverUrl = normalizeUrl(rawServerUrl)
      val deviceId = getDeviceId()
      val authHeader =
        "MediaBrowser Client=\"Mpv∞\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"1.0.3-debug\""
      val body = "{\"Username\":${jsonString(username)},\"Pw\":${jsonString(password)}}"
      val request = Request.Builder()
        .url("$serverUrl/Users/AuthenticateByName")
        .header("X-Emby-Authorization", authHeader)
        .header("Authorization", authHeader)
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
        JellyfinSession(serverUrl = serverUrl, userId = userId, accessToken = token)
      }
    }
  }

  suspend fun loadAudio(session: JellyfinSession, limit: Int = 200): Result<List<JellyfinTrack>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val fields = "PrimaryImageAspectRatio,MediaSources,RunTimeTicks,Album,AlbumArtist,Artists,ImageTags"
        val url =
          "${session.serverUrl}/Users/${session.userId}/Items" +
            "?IncludeItemTypes=Audio&Recursive=true&Fields=$fields&StartIndex=0&Limit=$limit" +
            "&SortBy=SortName&SortOrder=Ascending"
        val request = Request.Builder()
          .url(url)
          .header("X-Emby-Token", session.accessToken)
          .header("X-MediaBrowser-Token", session.accessToken)
          .build()

        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IOException("Jellyfin library request failed: HTTP ${response.code}")
          }
          val root = json.parseToJsonElement(response.body.string()).jsonObject
          val items = root["Items"]?.jsonArray ?: JsonArray(emptyList())
          items.mapNotNull { parseTrack(it.jsonObject, session) }
        }
      }
    }

  private fun parseTrack(obj: JsonObject, session: JellyfinSession): JellyfinTrack? {
    val id = obj["Id"]?.jsonPrimitive?.content ?: return null
    val name = obj["Name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
    val artist = obj["AlbumArtist"]?.jsonPrimitive?.content
      ?: obj["Artists"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
      ?: "Unknown artist"
    val album = obj["Album"]?.jsonPrimitive?.content ?: "Unknown album"
    val ticks = obj["RunTimeTicks"]?.jsonPrimitive?.longOrNull ?: 0L
    val tag = obj["ImageTags"]?.jsonObject?.get("Primary")?.jsonPrimitive?.content
    val encodedToken = URLEncoder.encode(session.accessToken, Charsets.UTF_8.name())
    val artwork = tag?.let {
      "${session.serverUrl}/Items/$id/Images/Primary?maxWidth=600&quality=90&tag=$it&api_key=$encodedToken"
    }
    val stream =
      "${session.serverUrl}/Audio/$id/stream?static=true&api_key=$encodedToken"
    return JellyfinTrack(
      id = id,
      title = name,
      artist = artist,
      album = album,
      durationMs = ticks / 10_000L,
      artworkUrl = artwork,
      streamUrl = stream,
    )
  }

  private fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim().removeSuffix("/")
    require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      "Enter a complete Jellyfin URL, for example https://jellyfin.example.com"
    }
    return trimmed
  }

  private fun getDeviceId(): String {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_DEVICE_ID, null)
    if (!existing.isNullOrBlank()) return existing
    val created = UUID.randomUUID().toString().replace("-", "")
    prefs.edit().putString(KEY_DEVICE_ID, created).apply()
    return created
  }

  private fun jsonString(value: String): String =
    Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(value))

  private companion object {
    const val PREFS = "jellyfin_debug"
    const val KEY_DEVICE_ID = "device_id"
  }
}
