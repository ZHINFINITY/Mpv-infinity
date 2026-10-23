/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubtitleTranslationProvidersTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val key = "AIzaSy-secret-1234567890"

  @Test
  fun providerLabelsRoundTripAndUnknownFallsBackToGoogle() {
    assertEquals(listOf("Google Translate", "OpenAI-compatible", "DeepL", "Gemini"), SubtitleTranslationProvider.labels)
    SubtitleTranslationProvider.entries.forEach { assertEquals(it, SubtitleTranslationProvider.fromLabel(it.label)) }
    assertEquals(SubtitleTranslationProvider.GEMINI, SubtitleTranslationProvider.fromLabel(" gemini "))
    assertEquals(SubtitleTranslationProvider.GOOGLE, SubtitleTranslationProvider.fromLabel(""))
  }

  @Test
  fun googleDefaultEndpointLeftInSharedFieldNeverReachesKeyedProviders() {
    // The shared endpoint preference defaults to Google's URL: Gemini/DeepL must not use it.
    val googleDefault = SubtitleTranslationProviders.GOOGLE_FREE_ENDPOINT
    assertEquals(
      SubtitleTranslationProviders.GEMINI_BASE_ENDPOINT,
      SubtitleTranslationProviders.effectiveEndpoint(SubtitleTranslationProvider.GEMINI, googleDefault, key),
    )
    assertEquals(
      SubtitleTranslationProviders.DEEPL_FREE_ENDPOINT,
      SubtitleTranslationProviders.effectiveEndpoint(SubtitleTranslationProvider.DEEPL, googleDefault, "abc:fx"),
    )
    assertEquals(
      SubtitleTranslationProviders.DEEPL_PRO_ENDPOINT,
      SubtitleTranslationProviders.effectiveEndpoint(SubtitleTranslationProvider.DEEPL, "", "pro-key"),
    )
    assertEquals(
      "https://proxy.example/v1",
      SubtitleTranslationProviders.effectiveEndpoint(SubtitleTranslationProvider.GEMINI, "https://proxy.example/v1", key),
    )
    assertEquals(
      SubtitleTranslationProviders.GEMINI_BASE_ENDPOINT,
      SubtitleTranslationProviders.endpointAfterProviderChange(SubtitleTranslationProvider.GEMINI, googleDefault),
    )
    assertEquals(
      "https://my.server/v1",
      SubtitleTranslationProviders.endpointAfterProviderChange(SubtitleTranslationProvider.DEEPL, "https://my.server/v1"),
    )
  }

  @Test
  fun openAiCompatibleUsesAssistantOnlyWhenItHasNoEndpointOrKey() {
    val p = SubtitleTranslationProvider.OPENAI_COMPATIBLE
    assertTrue(SubtitleTranslationProviders.usesAssistantFallback(p, SubtitleTranslationProviders.GOOGLE_FREE_ENDPOINT, ""))
    assertTrue(SubtitleTranslationProviders.usesAssistantFallback(p, "", ""))
    assertFalse(SubtitleTranslationProviders.usesAssistantFallback(p, "", "sk-key"))
    assertFalse(SubtitleTranslationProviders.usesAssistantFallback(p, "http://192.168.1.5:1234/v1", ""))
    assertFalse(SubtitleTranslationProviders.usesAssistantFallback(SubtitleTranslationProvider.GEMINI, "", ""))
    assertFalse(SubtitleTranslationProviders.usesAssistantFallback(SubtitleTranslationProvider.DEEPL, "", ""))
  }

  @Test
  fun languageMappingCoversNamesTagsAndDeeplVariants() {
    assertEquals("en", SubtitleTranslationProviders.languageCode("English"))
    assertEquals("zh-TW", SubtitleTranslationProviders.languageCode("zh_tw"))
    assertEquals("zh-CN", SubtitleTranslationProviders.languageCode("Chinese (Simplified)"))
    assertEquals("id", SubtitleTranslationProviders.languageCode("id-ID"))
    assertEquals("EN-US", SubtitleTranslationProviders.deeplTargetLanguage("en"))
    assertEquals("PT-BR", SubtitleTranslationProviders.deeplTargetLanguage("Portuguese"))
    assertEquals("PT-PT", SubtitleTranslationProviders.deeplTargetLanguage("pt-PT"))
    assertEquals("ZH-HANS", SubtitleTranslationProviders.deeplTargetLanguage("zh-CN"))
    assertEquals("ZH-HANT", SubtitleTranslationProviders.deeplTargetLanguage("zh-TW"))
    assertEquals("JA", SubtitleTranslationProviders.deeplTargetLanguage("Japanese"))
    assertEquals("Japanese", SubtitleTranslationProviders.languageDisplayName("ja"))
    try {
      SubtitleTranslationProviders.languageCode("  ")
      fail("blank language must be rejected")
    } catch (_: IllegalArgumentException) {
    }
  }

  @Test
  fun deeplRequestUsesHeaderAuthAndJsonBody() {
    val request = SubtitleTranslationProviders.deeplRequest("Hello\nworld", "en", SubtitleTranslationProviders.DEEPL_FREE_ENDPOINT, "k:fx")
    assertEquals(SubtitleTranslationProviders.DEEPL_FREE_ENDPOINT, request.url)
    assertEquals("DeepL-Auth-Key k:fx", request.headers["Authorization"])
    val body = json.parseToJsonElement(request.jsonBody!!).jsonObject
    assertEquals("Hello\nworld", body["text"]!!.jsonArray.single().jsonPrimitive.content)
    assertEquals("EN-US", body["target_lang"]!!.jsonPrimitive.content)
    assertFalse(request.url.contains("k:fx"))
    // A base URL gains the translate route.
    assertEquals(
      "https://api.deepl.com/v2/translate",
      SubtitleTranslationProviders.deeplRequest("x", "de", "https://api.deepl.com", "k").url,
    )
  }

  @Test
  fun geminiRequestUsesHeaderKeyModelRouteAndSystemInstruction() {
    val request =
      SubtitleTranslationProviders.geminiRequest("Bonjour", "en", SubtitleTranslationProviders.GEMINI_BASE_ENDPOINT, key, "models/gemini-3.8-flash")
    assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", request.url)
    assertEquals(key, request.headers["x-goog-api-key"])
    assertFalse(request.url.contains(key))
    val body = json.parseToJsonElement(request.jsonBody!!).jsonObject
    assertTrue(body["systemInstruction"].toString().contains("English"))
    assertEquals("Bonjour", body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    // Newer Gemini models ignore sampling parameters, so none are sent.
    assertNull(body["generationConfig"])
    val defaultModel = SubtitleTranslationProviders.geminiRequest("x", "en", SubtitleTranslationProviders.GEMINI_BASE_ENDPOINT, key, "")
    assertTrue(defaultModel.url.endsWith("/models/${SubtitleTranslationProviders.GEMINI_DEFAULT_MODEL}:generateContent"))
  }

  @Test
  fun keyedProvidersRejectMissingKeysWithClearMessages() {
    assertMessage("DeepL needs an API key") { SubtitleTranslationProviders.deeplRequest("x", "en", SubtitleTranslationProviders.DEEPL_PRO_ENDPOINT, "") }
    assertMessage("Gemini needs an API key") { SubtitleTranslationProviders.geminiRequest("x", "en", SubtitleTranslationProviders.GEMINI_BASE_ENDPOINT, " ", "") }
    assertMessage("Enter a model name") {
      SubtitleTranslationProviders.openAiCompatibleRequest("x", "en", "https://api.example/v1", "k", "")
    }
    assertMessage("endpoint is not a valid http(s) URL") {
      SubtitleTranslationProviders.geminiRequest("x", "en", "ftp://bad", key, "")
    }
  }

  @Test
  fun openAiCompatibleRequestBuildsChatCompletionsWithBearer() {
    val request = SubtitleTranslationProviders.openAiCompatibleRequest("Hola", "en", "https://api.example/v1/", "sk-1", "my-model")
    assertEquals("https://api.example/v1/chat/completions", request.url)
    assertEquals("Bearer sk-1", request.headers["Authorization"])
    val body = json.parseToJsonElement(request.jsonBody!!).jsonObject
    assertEquals("my-model", body["model"]!!.jsonPrimitive.content)
    assertEquals(2, body["messages"]!!.jsonArray.size)
    val local = SubtitleTranslationProviders.openAiCompatibleRequest("Hola", "en", "http://10.0.0.2:1234/v1/chat/completions", "", "m")
    assertEquals("http://10.0.0.2:1234/v1/chat/completions", local.url)
    assertNull(local.headers["Authorization"])
  }

  @Test
  fun responsesParseForEveryProvider() {
    assertEquals("Hello world", SubtitleTranslationProviders.parseGoogle(json, """[[["Hello ","Hola",null],["world","mundo",null]],null,"es"]"""))
    assertEquals("Hi", SubtitleTranslationProviders.parseGoogle(json, """{"data":{"translations":[{"translatedText":"Hi"}]}}"""))
    assertEquals("Hallo", SubtitleTranslationProviders.parseDeepl(json, """{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""))
    assertEquals(
      "Hello",
      SubtitleTranslationProviders.parseGemini(
        json,
        """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"Hello"}],"role":"model"},"finishReason":"STOP"}]}""",
      ),
    )
    assertEquals("Hi there", SubtitleTranslationProviders.parseOpenAi(json, """{"choices":[{"message":{"role":"assistant","content":"<think>x</think>\n```\nHi there\n```"}}]}"""))
    assertMessage("Gemini blocked the request (SAFETY)") {
      SubtitleTranslationProviders.parseGemini(json, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
    }
    assertMessage("DeepL returned") { SubtitleTranslationProviders.parseDeepl(json, "<html>") }
  }

  @Test
  fun httpFailuresAreShortAndNeverContainTheKey() {
    val body = """{"error":{"code":400,"message":"API key not valid. key=$key Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
    val message = SubtitleTranslationProviders.httpFailureMessage(SubtitleTranslationProvider.GEMINI, 403, body, key, json)
    assertTrue(message, message.startsWith("Gemini rejected the API key"))
    assertFalse(message, message.contains(key))
    assertEquals(
      "DeepL character quota is used up",
      SubtitleTranslationProviders.httpFailureMessage(SubtitleTranslationProvider.DEEPL, 456, "", "k", json),
    )
    assertTrue(
      SubtitleTranslationProviders.httpFailureMessage(SubtitleTranslationProvider.OPENAI_COMPATIBLE, 429, "{}", "k", json)
        .contains("rate limit"),
    )
    val status = SubtitleTranslationFailurePolicy.statusMessage(SubtitleTranslationException(message))
    assertTrue(status.startsWith("Translation failed: Gemini"))
    assertEquals("Translation failed: translation service error", SubtitleTranslationFailurePolicy.statusMessage(RuntimeException("https://x/?key=1")))
  }

  private fun assertMessage(fragment: String, block: () -> Unit) {
    try {
      block()
      fail("expected SubtitleTranslationException containing '$fragment'")
    } catch (e: SubtitleTranslationException) {
      assertTrue("'${e.message}' should contain '$fragment'", e.message.orEmpty().contains(fragment))
    }
  }

  @Test
  fun redactHidesEveryNonBlankKeyIncludingShortOnes() {
    val long = SubtitleTranslationProviders.redact("bad key $key for request", key)
    assertFalse(long.contains(key))
    assertTrue(long.contains("••••"))
    val short = SubtitleTranslationProviders.redact("key abc is not valid", "abc")
    assertFalse(short.contains("abc"))
    assertEquals("••••", short)
    assertEquals("quota exceeded", SubtitleTranslationProviders.redact("quota exceeded", "xyz"))
    assertEquals("quota exceeded", SubtitleTranslationProviders.redact("quota exceeded", "  "))
    assertEquals("url ?key=•••• end", SubtitleTranslationProviders.redact("url ?key=hidden end", ""))
  }
}
