/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import app.infinity.mpvz.preferences.AiPreferences
import app.infinity.mpvz.preferences.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates subtitle text with the provider chosen in Settings > AI Integration (Google Translate,
 * OpenAI-compatible, DeepL or Gemini) without changing playback. API keys are sent only in request
 * headers and never appear in URLs, exception messages or logs.
 */
class EmbeddedSubtitleTranslator(
  private val preferences: AiPreferences,
  private val client: OkHttpClient,
  private val json: Json,
) {
  private val cache = ConcurrentHashMap<String, String>()

  /** The provider and settings used for a request, read fresh from preferences. */
  data class Settings(
    val provider: SubtitleTranslationProvider,
    val endpoint: String,
    val apiKey: String,
    val model: String,
  )

  fun currentSettings(): Settings {
    val provider = SubtitleTranslationProvider.fromLabel(preferences.embeddedSubtitleTranslationProvider.get())
    val key = preferences.embeddedSubtitleTranslationApiKey.get().trim()
    val configured = preferences.embeddedSubtitleTranslationEndpoint.get().trim()
    val model =
      preferences.embeddedSubtitleTranslationModel.get().trim().ifBlank {
        when (provider) {
          SubtitleTranslationProvider.GEMINI -> SubtitleTranslationProviders.GEMINI_DEFAULT_MODEL
          SubtitleTranslationProvider.OPENAI_COMPATIBLE -> preferences.selectedModelFor(AiProvider.OPENAI).get().trim()
          else -> ""
        }
      }
    return Settings(provider, SubtitleTranslationProviders.effectiveEndpoint(provider, configured, key), key, model)
  }

  /**
   * True when the OpenAI-compatible option has neither its own endpoint nor its own key, so callers
   * should use the AI assistant provider configured above it (the behaviour before this option had
   * dedicated settings).
   */
  fun usesAssistantFallback(): Boolean =
    SubtitleTranslationProviders.usesAssistantFallback(
      SubtitleTranslationProvider.fromLabel(preferences.embeddedSubtitleTranslationProvider.get()),
      preferences.embeddedSubtitleTranslationEndpoint.get(),
      preferences.embeddedSubtitleTranslationApiKey.get(),
    )

  /** Translates [text] into [targetLanguage] with the selected provider. */
  suspend fun translate(text: String, targetLanguage: String): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        if (text.isBlank()) throw SubtitleTranslationException("Subtitle text is empty")
        val settings = currentSettings()
        val language = SubtitleTranslationProviders.languageCode(targetLanguage)
        val cacheKey = "${settings.provider.name}:${settings.model}:$language:${text.trim()}"
        cache[cacheKey]?.let { return@runCatching it }
        val translated =
          when (settings.provider) {
            SubtitleTranslationProvider.GOOGLE -> translateGoogleWithFallback(text, language, settings)
            else -> execute(settings, requestFor(settings, text, language))
          }
        if (cache.size > MAX_CACHE) cache.clear()
        cache[cacheKey] = translated
        translated
      }.recoverCatching { failure -> throw safeFailure(failure) }
    }

  /** Kept for existing callers; uses the selected provider like [translate]. */
  suspend fun translateGoogle(text: String, targetLanguage: String): Result<String> = translate(text, targetLanguage)

  /** Only safe, key-free messages leave this class. */
  private fun safeFailure(failure: Throwable): SubtitleTranslationException {
    val label = SubtitleTranslationProvider.fromLabel(preferences.embeddedSubtitleTranslationProvider.get()).label
    return when (failure) {
      is SubtitleTranslationException -> failure
      is IllegalArgumentException ->
        SubtitleTranslationException(failure.message?.takeIf { it.length < 120 } ?: "Subtitle translation settings are invalid")
      is IOException -> SubtitleTranslationException("$label could not be reached (check the connection or endpoint)")
      else -> SubtitleTranslationException("$label translation failed")
    }
  }

  private fun requestFor(settings: Settings, text: String, language: String): TranslationHttpRequest =
    when (settings.provider) {
      SubtitleTranslationProvider.GOOGLE ->
        SubtitleTranslationProviders.googleRequest(text, language, settings.endpoint, settings.apiKey)
      SubtitleTranslationProvider.DEEPL ->
        SubtitleTranslationProviders.deeplRequest(text, language, settings.endpoint, settings.apiKey)
      SubtitleTranslationProvider.GEMINI ->
        SubtitleTranslationProviders.geminiRequest(text, language, settings.endpoint, settings.apiKey, settings.model)
      SubtitleTranslationProvider.OPENAI_COMPATIBLE ->
        SubtitleTranslationProviders.openAiCompatibleRequest(
          text,
          language,
          settings.endpoint,
          settings.apiKey,
          settings.model,
        )
    }

  /** The free Google endpoint has two hosts; a user-entered endpoint is tried first. */
  private fun translateGoogleWithFallback(text: String, language: String, settings: Settings): String {
    val endpoints =
      listOf(
        settings.endpoint,
        SubtitleTranslationProviders.GOOGLE_FREE_ENDPOINT,
        "https://translate.google.com/translate_a/single",
      ).filter(String::isNotBlank).distinct()
    var last: Throwable? = null
    for (endpoint in endpoints) {
      try {
        return execute(settings, SubtitleTranslationProviders.googleRequest(text, language, endpoint, settings.apiKey))
      } catch (failure: Throwable) {
        last = failure
        // A rejected key will not succeed on another host.
        if (failure is SubtitleTranslationException && failure.message?.contains("API key") == true) break
      }
    }
    throw last ?: SubtitleTranslationException("No Google Translate endpoint is available")
  }

  private fun execute(settings: Settings, request: TranslationHttpRequest): String {
    val builder = Request.Builder().url(request.url)
    request.headers.forEach { (name, value) -> builder.header(name, value) }
    val body = request.jsonBody
    if (body != null) builder.post(body.toRequestBody(JSON_MEDIA)) else builder.get()
    return client.newCall(builder.build()).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw SubtitleTranslationException(
          SubtitleTranslationProviders.httpFailureMessage(settings.provider, response.code, text, settings.apiKey, json),
        )
      }
      when (settings.provider) {
        SubtitleTranslationProvider.GOOGLE -> SubtitleTranslationProviders.parseGoogle(json, text)
        SubtitleTranslationProvider.DEEPL -> SubtitleTranslationProviders.parseDeepl(json, text)
        SubtitleTranslationProvider.GEMINI -> SubtitleTranslationProviders.parseGemini(json, text)
        SubtitleTranslationProvider.OPENAI_COMPATIBLE -> SubtitleTranslationProviders.parseOpenAi(json, text)
      }
    }
  }

  private companion object {
    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    const val MAX_CACHE = 2_000
  }
}
