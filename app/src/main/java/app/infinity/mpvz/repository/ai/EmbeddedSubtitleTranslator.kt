/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.repository.ai

import app.infinity.mpvz.preferences.AiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Translates individual embedded soft-subtitle cues without changing the playback engine. */
class EmbeddedSubtitleTranslator(
  private val preferences: AiPreferences,
  private val client: OkHttpClient,
  private val json: Json,
) {
  suspend fun translateGoogle(
    text: String,
    targetLanguage: String,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val endpoint = preferences.embeddedSubtitleTranslationEndpoint.get().trim()
        require(endpoint.isNotBlank()) { "Subtitle translation endpoint is empty" }
        val url =
          endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", targetLanguage)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        val response = client.newCall(Request.Builder().url(url).get().build()).execute()
        val body = response.body.string()
        check(response.isSuccessful) { "Translation endpoint returned HTTP ${response.code}" }
        val root = json.parseToJsonElement(body).jsonArray
        root.firstOrNull()?.jsonArray
          ?.mapNotNull { segment -> segment.jsonArray.firstOrNull()?.jsonPrimitive?.content }
          ?.joinToString("")
          ?.takeIf(String::isNotBlank)
          ?: error("Translation endpoint returned no text")
      }
    }
}
