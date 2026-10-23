/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** The subtitle translation providers offered in Settings > AI Integration. */
enum class SubtitleTranslationProvider(val label: String) {
  GOOGLE("Google Translate"),
  OPENAI_COMPATIBLE("OpenAI-compatible"),
  DEEPL("DeepL"),
  GEMINI("Gemini"),
  ;

  companion object {
    val labels: List<String> = entries.map { it.label }

    fun fromLabel(value: String?): SubtitleTranslationProvider {
      val text = value?.trim().orEmpty()
      return entries.firstOrNull { it.label.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
        ?: GOOGLE
    }
  }
}

/** A provider request; the API key only ever travels in [headers], never in [url]. */
data class TranslationHttpRequest(
  val url: String,
  val headers: Map<String, String>,
  /** JSON request body; `null` means a GET request. */
  val jsonBody: String?,
)

/** A translation failure whose [message] is safe to show and log (no key, no URL). */
class SubtitleTranslationException(message: String) : Exception(message)

/**
 * Pure request building, response parsing and error mapping for subtitle translation.
 * Everything here is JVM-testable; the network call lives in [EmbeddedSubtitleTranslator].
 */
object SubtitleTranslationProviders {
  const val GOOGLE_FREE_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
  private const val GOOGLE_FREE_ALT_ENDPOINT = "https://translate.google.com/translate_a/single"
  const val GOOGLE_CLOUD_ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
  const val DEEPL_FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate"
  const val DEEPL_PRO_ENDPOINT = "https://api.deepl.com/v2/translate"
  const val GEMINI_BASE_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta"

  /** Google's hot-swapped Flash alias, so the default never points at a retired model. */
  const val GEMINI_DEFAULT_MODEL = "gemini-flash-latest"
  const val OPENAI_DEFAULT_BASE = "https://api.openai.com/v1"

  private val knownDefaults =
    setOf(
      GOOGLE_FREE_ENDPOINT,
      GOOGLE_FREE_ALT_ENDPOINT,
      DEEPL_FREE_ENDPOINT,
      DEEPL_PRO_ENDPOINT,
      GEMINI_BASE_ENDPOINT,
      OPENAI_DEFAULT_BASE,
    )

  /** True when [endpoint] is blank or another provider's built-in default (not a user URL). */
  fun isBuiltInEndpoint(endpoint: String): Boolean {
    val value = endpoint.trim().trimEnd('/')
    return value.isBlank() || knownDefaults.any { it.equals(value, ignoreCase = true) }
  }

  /** The endpoint shown/used for [provider] when the user has not entered their own. */
  fun defaultEndpoint(provider: SubtitleTranslationProvider, apiKey: String = ""): String =
    when (provider) {
      SubtitleTranslationProvider.GOOGLE -> GOOGLE_FREE_ENDPOINT
      SubtitleTranslationProvider.DEEPL -> if (isDeeplFreeKey(apiKey)) DEEPL_FREE_ENDPOINT else DEEPL_PRO_ENDPOINT
      SubtitleTranslationProvider.GEMINI -> GEMINI_BASE_ENDPOINT
      SubtitleTranslationProvider.OPENAI_COMPATIBLE -> ""
    }

  /**
   * The endpoint to call. A blank value or a different provider's built-in default (left over from
   * switching providers, since all providers share one field) resolves to [provider]'s default.
   */
  fun effectiveEndpoint(provider: SubtitleTranslationProvider, configured: String, apiKey: String): String {
    val value = configured.trim()
    if (!isBuiltInEndpoint(value)) return value
    if (provider == SubtitleTranslationProvider.GOOGLE && value.equals(GOOGLE_FREE_ALT_ENDPOINT, true)) return value
    if (provider == SubtitleTranslationProvider.OPENAI_COMPATIBLE && value.trimEnd('/').equals(OPENAI_DEFAULT_BASE, true)) {
      return OPENAI_DEFAULT_BASE
    }
    return defaultEndpoint(provider, apiKey)
  }

  /** The value to store in the shared endpoint field after the user picks [newProvider]. */
  fun endpointAfterProviderChange(newProvider: SubtitleTranslationProvider, current: String): String =
    if (isBuiltInEndpoint(current)) defaultEndpoint(newProvider) else current.trim()

  /**
   * OpenAI-compatible with no endpoint and no key of its own keeps the earlier behaviour: it uses the
   * AI assistant provider configured above (key + model) instead of a dedicated translation API.
   */
  fun usesAssistantFallback(provider: SubtitleTranslationProvider, configured: String, apiKey: String): Boolean =
    provider == SubtitleTranslationProvider.OPENAI_COMPATIBLE && isBuiltInEndpoint(configured) && apiKey.isBlank()

  fun isDeeplFreeKey(apiKey: String): Boolean = apiKey.trim().endsWith(":fx")

  // ---- Languages -------------------------------------------------------------------------------

  private val languageNames =
    mapOf(
      "english" to "en", "arabic" to "ar", "bengali" to "bn", "chinese (simplified)" to "zh-CN",
      "chinese (traditional)" to "zh-TW", "chinese" to "zh-CN", "french" to "fr", "german" to "de",
      "hindi" to "hi", "indonesian" to "id", "italian" to "it", "japanese" to "ja", "korean" to "ko",
      "persian" to "fa", "polish" to "pl", "portuguese" to "pt", "portuguese (brazil)" to "pt-BR",
      "russian" to "ru", "spanish" to "es", "tamil" to "ta", "telugu" to "te", "thai" to "th",
      "turkish" to "tr", "ukrainian" to "uk", "urdu" to "ur", "vietnamese" to "vi", "dutch" to "nl",
    )

  /** Normalises a language name or tag to a BCP-47-ish code ("English" -> "en", "zh_tw" -> "zh-TW"). */
  fun languageCode(language: String): String {
    val value = language.trim()
    require(value.isNotBlank()) { "No subtitle translation target language is set" }
    languageNames[value.lowercase(Locale.ROOT)]?.let { return it }
    val parts = value.replace('_', '-').split('-').filter(String::isNotBlank)
    val base = parts.first().lowercase(Locale.ROOT)
    require(base.length in 2..3 && base.all(Char::isLetter)) { "Subtitle translation target language is invalid" }
    val region = parts.getOrNull(1)
    return when {
      base == "zh" && region != null -> "zh-" + region.uppercase(Locale.ROOT).let { if (it == "HANT") "TW" else if (it == "HANS") "CN" else it }
      base == "pt" && region != null -> "pt-" + region.uppercase(Locale.ROOT)
      else -> base
    }
  }

  /** Google uses "zh-CN"/"zh-TW" and otherwise the base language. */
  fun googleLanguage(language: String): String = languageCode(language)

  /** DeepL target codes (EN-US, PT-BR, ZH-HANS, ...); bare EN/PT/ZH are deprecated targets. */
  fun deeplTargetLanguage(language: String): String {
    val code = languageCode(language)
    return when (code) {
      "en" -> "EN-US"
      "pt", "pt-BR" -> "PT-BR"
      "pt-PT" -> "PT-PT"
      "zh", "zh-CN", "zh-SG" -> "ZH-HANS"
      "zh-TW", "zh-HK", "zh-MO" -> "ZH-HANT"
      else -> code.substringBefore('-').uppercase(Locale.ROOT)
    }
  }

  /** English display name for LLM prompts ("ja" -> "Japanese", "zh-TW" -> "Chinese (Taiwan)"). */
  fun languageDisplayName(language: String): String {
    val code = languageCode(language)
    val name = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
    return name.takeIf { it.isNotBlank() && !it.equals(code, true) } ?: code
  }

  fun translationInstruction(language: String): String =
    "You translate video subtitles. Translate the user's subtitle text into ${languageDisplayName(language)}. " +
      "Return only the translated subtitle text, preserving line breaks. Do not add quotes, notes, " +
      "romanization or commentary."

  // ---- Requests --------------------------------------------------------------------------------

  private fun requireHttpUrl(url: String, label: String): URI {
    val uri = runCatching { URI(url.trim()) }.getOrNull()
    if (uri == null || !uri.isAbsolute || uri.host.isNullOrBlank() ||
      !(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
    ) {
      throw SubtitleTranslationException("$label endpoint is not a valid http(s) URL")
    }
    if (uri.userInfo != null) throw SubtitleTranslationException("$label endpoint must not contain credentials")
    return uri
  }

  private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

  fun googleRequest(text: String, language: String, endpoint: String, apiKey: String): TranslationHttpRequest {
    val target = googleLanguage(language)
    val url = endpoint.trim()
    requireHttpUrl(url, "Google Translate")
    // The official Cloud Translation v2 API takes a key; the free web endpoint does not.
    if (url.contains("/language/translate/v2", ignoreCase = true)) {
      if (apiKey.isBlank()) throw SubtitleTranslationException("Google Cloud Translation needs an API key")
      val body =
        buildJsonObject {
          put("q", text)
          put("target", target)
          put("format", "text")
        }
      return TranslationHttpRequest(
        url,
        mapOf("X-Goog-Api-Key" to apiKey.trim(), "Content-Type" to "application/json", "Accept" to "application/json"),
        body.toString(),
      )
    }
    val separator = if (url.contains('?')) "&" else "?"
    return TranslationHttpRequest(
      "$url${separator}client=gtx&sl=auto&tl=${enc(target)}&dt=t&q=${enc(text)}",
      mapOf("Accept" to "application/json"),
      null,
    )
  }

  fun deeplRequest(text: String, language: String, endpoint: String, apiKey: String): TranslationHttpRequest {
    if (apiKey.isBlank()) throw SubtitleTranslationException("DeepL needs an API key")
    var url = endpoint.trim().trimEnd('/')
    requireHttpUrl(url, "DeepL")
    if (!url.endsWith("/translate", ignoreCase = true)) url = if (url.endsWith("/v2", true)) "$url/translate" else "$url/v2/translate"
    val body =
      buildJsonObject {
        put("text", buildJsonArray { add(JsonPrimitive(text)) })
        put("target_lang", deeplTargetLanguage(language))
        put("preserve_formatting", true)
      }
    return TranslationHttpRequest(
      url,
      mapOf(
        "Authorization" to "DeepL-Auth-Key ${apiKey.trim()}",
        "Content-Type" to "application/json",
        "Accept" to "application/json",
      ),
      body.toString(),
    )
  }

  fun geminiRequest(text: String, language: String, endpoint: String, apiKey: String, model: String): TranslationHttpRequest {
    if (apiKey.isBlank()) throw SubtitleTranslationException("Gemini needs an API key")
    val base = endpoint.trim().trimEnd('/')
    requireHttpUrl(base, "Gemini")
    if (base.contains("key=", ignoreCase = true)) throw SubtitleTranslationException("Put the Gemini key in the API key field, not the endpoint")
    val url =
      if (base.contains(":generateContent", ignoreCase = true)) {
        base
      } else {
        val id = model.trim().removePrefix("models/").ifBlank { GEMINI_DEFAULT_MODEL }
        "$base/models/${enc(id)}:generateContent"
      }
    val body =
      buildJsonObject {
        put("systemInstruction", buildJsonObject { put("parts", textParts(translationInstruction(language))) })
        put(
          "contents",
          buildJsonArray {
            add(
              buildJsonObject {
                put("role", "user")
                put("parts", textParts(text))
              },
            )
          },
        )
      }
    return TranslationHttpRequest(
      url,
      mapOf("x-goog-api-key" to apiKey.trim(), "Content-Type" to "application/json", "Accept" to "application/json"),
      body.toString(),
    )
  }

  fun openAiCompatibleRequest(
    text: String,
    language: String,
    endpoint: String,
    apiKey: String,
    model: String,
  ): TranslationHttpRequest {
    val base = endpoint.trim().trimEnd('/')
    if (base.isBlank()) throw SubtitleTranslationException("Enter the OpenAI-compatible endpoint, e.g. $OPENAI_DEFAULT_BASE")
    val uri = requireHttpUrl(base, "OpenAI-compatible")
    if (uri.query != null) throw SubtitleTranslationException("OpenAI-compatible endpoint must not contain query parameters")
    if (model.isBlank()) throw SubtitleTranslationException("Enter a model name for the OpenAI-compatible translator")
    val url = if (base.endsWith("/chat/completions", ignoreCase = true)) base else "$base/chat/completions"
    val body =
      buildJsonObject {
        put("model", model.trim())
        put(
          "messages",
          buildJsonArray {
            add(
              buildJsonObject {
                put("role", "system")
                put("content", translationInstruction(language))
              },
            )
            add(
              buildJsonObject {
                put("role", "user")
                put("content", text)
              },
            )
          },
        )
      }
    val headers = linkedMapOf("Content-Type" to "application/json", "Accept" to "application/json")
    if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${apiKey.trim()}"
    return TranslationHttpRequest(url, headers, body.toString())
  }

  private fun textParts(text: String): JsonArray =
    buildJsonArray { add(buildJsonObject { put("text", text) }) }

  // ---- Responses -------------------------------------------------------------------------------

  private fun JsonElement.obj(): JsonObject? = this as? JsonObject

  private fun JsonElement.arr(): JsonArray? = this as? JsonArray

  private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

  private fun parse(json: Json, body: String, label: String): JsonElement =
    runCatching { json.parseToJsonElement(body) }.getOrElse {
      throw SubtitleTranslationException("$label returned an unreadable response")
    }

  private fun nonBlank(value: String?, label: String): String =
    value?.trim()?.takeIf(String::isNotBlank) ?: throw SubtitleTranslationException("$label returned no translation")

  fun parseGoogle(json: Json, body: String): String {
    val root = parse(json, body, "Google Translate")
    root.obj()?.let { obj ->
      // Cloud Translation v2: {"data":{"translations":[{"translatedText":"..."}]}}
      val text = obj["data"]?.obj()?.get("translations")?.arr()?.firstOrNull()?.obj()?.get("translatedText")?.str()
      return nonBlank(text, "Google Translate")
    }
    // Free endpoint: [[["translated","original",...],...],...]
    val joined = root.arr()?.firstOrNull()?.arr()?.mapNotNull { it.arr()?.firstOrNull()?.str() }?.joinToString("")
    return nonBlank(joined, "Google Translate")
  }

  fun parseDeepl(json: Json, body: String): String {
    val text = parse(json, body, "DeepL").obj()?.get("translations")?.arr()?.firstOrNull()?.obj()?.get("text")?.str()
    return nonBlank(text, "DeepL")
  }

  fun parseGemini(json: Json, body: String): String {
    val root = parse(json, body, "Gemini").obj()
    val candidate = root?.get("candidates")?.arr()?.firstOrNull()?.obj()
    if (candidate == null) {
      val reason = root?.get("promptFeedback")?.obj()?.get("blockReason")?.str()
      throw SubtitleTranslationException(if (reason != null) "Gemini blocked the request ($reason)" else "Gemini returned no translation")
    }
    val parts = candidate["content"]?.obj()?.get("parts")?.arr().orEmpty()
    // Thought summaries (thought=true) are never subtitle text.
    val text =
      parts.mapNotNull { part ->
        val p = part.obj() ?: return@mapNotNull null
        if ((p["thought"] as? JsonPrimitive)?.contentOrNull == "true") null else p["text"]?.str()
      }.joinToString("")
    if (text.isBlank()) {
      val finish = candidate["finishReason"]?.str()
      throw SubtitleTranslationException(if (finish != null && finish != "STOP") "Gemini stopped early ($finish)" else "Gemini returned no translation")
    }
    return cleanModelOutput(text)
  }

  fun parseOpenAi(json: Json, body: String): String {
    val message =
      parse(json, body, "OpenAI-compatible endpoint").obj()?.get("choices")?.arr()?.firstOrNull()?.obj()
        ?.get("message")?.obj()
    val content =
      when (val value = message?.get("content")) {
        is JsonPrimitive -> value.contentOrNull
        // Some compatible servers return content parts.
        is JsonArray -> value.mapNotNull { it.obj()?.get("text")?.str() }.joinToString("")
        else -> null
      }
    return cleanModelOutput(nonBlank(content, "OpenAI-compatible endpoint"))
  }

  /** Removes reasoning blocks and code fences some chat models wrap around their answer. */
  fun cleanModelOutput(raw: String): String {
    val withoutThinking = raw.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
    val unfenced = AiOutputSanitizer.stripCodeFence(withoutThinking.trim())
    return unfenced.trim()
  }

  // ---- Errors ----------------------------------------------------------------------------------

  /** A short user-facing reason for an HTTP failure. Never contains the URL; the key is redacted. */
  fun httpFailureMessage(
    provider: SubtitleTranslationProvider,
    code: Int,
    body: String,
    apiKey: String,
    json: Json,
  ): String {
    val label = provider.label
    val detail = providerErrorDetail(json, body)?.let { redact(it, apiKey) }?.take(160)
    val reason =
      when (code) {
        400 -> "rejected the request"
        401, 403 -> "rejected the API key"
        404 -> "endpoint or model was not found"
        413 -> "text is too long"
        429 -> "rate limit or quota reached"
        456 -> "character quota is used up"
        in 500..599 -> "service is unavailable (HTTP $code)"
        else -> "returned HTTP $code"
      }
    return if (detail.isNullOrBlank()) "$label $reason" else "$label $reason: $detail"
  }

  private fun providerErrorDetail(json: Json, body: String): String? {
    if (body.isBlank()) return null
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull()?.obj() ?: return null
    return when (val error = root["error"]) {
      is JsonObject -> error["message"]?.str()
      is JsonPrimitive -> error.contentOrNull
      else -> root["message"]?.str()
    }
  }

  fun redact(text: String, apiKey: String): String {
    val key = apiKey.trim()
    var out = if (key.length >= 4) text.replace(key, "••••") else text
    out = out.replace(Regex("(?i)(key=)[^&\\s]+"), "$1••••")
    return out
  }
}

/** How the player reports a per-cue translation failure. */
object SubtitleTranslationFailurePolicy {
  /** Original cues are shown without retrying for this long after a failure. */
  const val BACKOFF_MS = 15_000L
  const val STATUS_VISIBLE_MS = 6_000L

  /** A short, key-free status line such as "Translation failed: Gemini rejected the API key". */
  fun statusMessage(failure: Throwable?): String {
    val reason =
      (failure as? SubtitleTranslationException)?.message
        ?: failure?.message?.takeIf { it.isNotBlank() && it.length <= 120 && !it.contains("http", ignoreCase = true) }
        ?: "translation service error"
    return "Translation failed: ${reason.take(140)}"
  }
}
