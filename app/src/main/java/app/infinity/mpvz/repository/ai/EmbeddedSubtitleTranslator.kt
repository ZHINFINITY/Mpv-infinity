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
        require(text.isNotBlank()) { "Subtitle text is empty" }
        val language = targetLanguage.trim().lowercase().substringBefore('-').substringBefore('_')
        require(language.length in 2..8) { "Google Translate target language is invalid" }
        val configured = preferences.embeddedSubtitleTranslationEndpoint.get().trim()
        val endpoints =
          listOf(configured, "https://translate.googleapis.com/translate_a/single", "https://translate.google.com/translate_a/single")
            .filter(String::isNotBlank)
            .distinct()
        var lastFailure: Throwable? = null
        for (endpoint in endpoints) {
          try {
            val url =
              endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", language)
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text)
                .build()
            val translated =
              client.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Translation endpoint returned HTTP ${response.code}" }
                json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonArray
                  ?.mapNotNull { segment -> segment.jsonArray.firstOrNull()?.jsonPrimitive?.content }
                  ?.joinToString("")
                  ?.trim()
                  ?.takeIf(String::isNotBlank)
                  ?: throw IllegalStateException("Translation endpoint returned no text")
              }
            return@runCatching translated
          } catch (failure: Throwable) {
            lastFailure = failure
          }
        }
        throw lastFailure ?: IllegalStateException("No Google Translate endpoint is available")
      }
    }
}
