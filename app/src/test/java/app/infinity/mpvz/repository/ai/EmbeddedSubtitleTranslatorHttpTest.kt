/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import app.infinity.mpvz.preferences.AiPreferences
import app.infinity.mpvz.preferences.preference.Preference
import app.infinity.mpvz.preferences.preference.PreferenceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class EmbeddedSubtitleTranslatorHttpTest {
  private lateinit var server: MockWebServer
  private val json = Json { ignoreUnknownKeys = true }
  private val secret = "user-secret-key-0123456789"

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun translator(values: Map<String, String>) =
    EmbeddedSubtitleTranslator(
      AiPreferences(TranslatorPreferenceStore(values)),
      OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
      json,
    )

  private fun base() = server.url("/").toString().trimEnd('/')

  @Test
  fun geminiUsesSavedKeyInHeaderAndItsOwnEndpoint() =
    runBlocking {
      server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Hello"}]},"finishReason":"STOP"}]}"""))
      val t =
        translator(
          mapOf(
            "ai_embedded_subtitle_translation_provider" to "Gemini",
            "ai_embedded_subtitle_translation_endpoint" to "${base()}/v1beta",
            "ai_embedded_subtitle_translation_api_key" to secret,
          ),
        )
      assertFalse(t.usesAssistantFallback())
      assertEquals("Hello", t.translate("Bonjour", "English").getOrThrow())
      val request = server.takeRequest()
      assertEquals("/v1beta/models/${SubtitleTranslationProviders.GEMINI_DEFAULT_MODEL}:generateContent", request.path)
      assertEquals(secret, request.getHeader("x-goog-api-key"))
      assertFalse(request.path!!.contains(secret))
      assertEquals(1, server.requestCount)
    }

  @Test
  fun deeplUsesSavedKeyAndCorrectTargetCode() =
    runBlocking {
      server.enqueue(MockResponse().setBody("""{"translations":[{"text":"Hallo Welt"}]}"""))
      val t =
        translator(
          mapOf(
            "ai_embedded_subtitle_translation_provider" to "DeepL",
            "ai_embedded_subtitle_translation_endpoint" to "${base()}/v2/translate",
            "ai_embedded_subtitle_translation_api_key" to "$secret:fx",
          ),
        )
      assertEquals("Hallo Welt", t.translate("Hello world", "de").getOrThrow())
      val request = server.takeRequest()
      assertEquals("DeepL-Auth-Key $secret:fx", request.getHeader("Authorization"))
      assertTrue(request.body.readUtf8().contains("\"target_lang\":\"DE\""))
    }

  @Test
  fun openAiCompatibleUsesItsOwnEndpointKeyAndModel() =
    runBlocking {
      server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Good night"}}]}"""))
      val t =
        translator(
          mapOf(
            "ai_embedded_subtitle_translation_provider" to "OpenAI-compatible",
            "ai_embedded_subtitle_translation_endpoint" to "${base()}/v1",
            "ai_embedded_subtitle_translation_api_key" to secret,
            "ai_embedded_subtitle_translation_model" to "local-model",
          ),
        )
      assertFalse(t.usesAssistantFallback())
      assertEquals("Good night", t.translate("Buenas noches", "en").getOrThrow())
      val request = server.takeRequest()
      assertEquals("/v1/chat/completions", request.path)
      assertEquals("Bearer $secret", request.getHeader("Authorization"))
      assertTrue(request.body.readUtf8().contains("\"model\":\"local-model\""))
    }

  @Test
  fun rejectedKeyFailsClearlyWithoutFallingBackToGoogle() =
    runBlocking {
      server.enqueue(
        MockResponse().setResponseCode(403).setBody("""{"error":{"message":"API key not valid: $secret"}}"""),
      )
      val t =
        translator(
          mapOf(
            "ai_embedded_subtitle_translation_provider" to "Gemini",
            "ai_embedded_subtitle_translation_endpoint" to "${base()}/v1beta",
            "ai_embedded_subtitle_translation_api_key" to secret,
          ),
        )
      val failure = t.translate("Bonjour", "en").exceptionOrNull()!!
      assertTrue(failure is SubtitleTranslationException)
      assertTrue(failure.message!!, failure.message!!.startsWith("Gemini rejected the API key"))
      assertFalse(failure.message!!.contains(secret))
      // Exactly one call, to the Gemini endpoint: no silent free-Google retry.
      assertEquals(1, server.requestCount)
    }

  @Test
  fun missingKeyFailsBeforeAnyNetworkCall() =
    runBlocking {
      val t = translator(mapOf("ai_embedded_subtitle_translation_provider" to "DeepL"))
      val failure = t.translate("Hello", "de").exceptionOrNull()!!
      assertEquals("DeepL needs an API key", failure.message)
      assertEquals(0, server.requestCount)
    }

  @Test
  fun unconfiguredOpenAiCompatibleBorrowsTheAssistantProvider() {
    val t = translator(mapOf("ai_embedded_subtitle_translation_provider" to "OpenAI-compatible"))
    assertTrue(t.usesAssistantFallback())
  }

  @Test
  fun googleFreeEndpointStillWorksWithoutKey() =
    runBlocking {
      server.enqueue(MockResponse().setBody("""[[["Hello","Hola",null]],null,"es"]"""))
      val t =
        translator(
          mapOf(
            "ai_embedded_subtitle_translation_provider" to "Google Translate",
            "ai_embedded_subtitle_translation_endpoint" to "${base()}/translate_a/single",
          ),
        )
      assertEquals("Hello", t.translate("Hola", "en").getOrThrow())
      val path = server.takeRequest().path!!
      assertTrue(path, path.startsWith("/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q=Hola"))
    }
}

private class TranslatorPreferenceStore(
  private val values: Map<String, String>,
) : PreferenceStore {
  override fun getString(key: String, defaultValue: String): Preference<String> =
    TranslatorPreference(key, values[key] ?: defaultValue)

  override fun getLong(key: String, defaultValue: Long): Preference<Long> = TranslatorPreference(key, defaultValue)

  override fun getInt(key: String, defaultValue: Int): Preference<Int> = TranslatorPreference(key, defaultValue)

  override fun getFloat(key: String, defaultValue: Float): Preference<Float> = TranslatorPreference(key, defaultValue)

  override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = TranslatorPreference(key, defaultValue)

  override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
    TranslatorPreference(key, defaultValue)

  override fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T> = TranslatorPreference(key, defaultValue)

  override fun getAll(): Map<String, *> = values
}

private class TranslatorPreference<T>(
  private val preferenceKey: String,
  initialValue: T,
) : Preference<T> {
  private val state = MutableStateFlow(initialValue)

  override fun key(): String = preferenceKey

  override fun get(): T = state.value

  override fun set(value: T) {
    state.value = value
  }

  override fun isSet(): Boolean = true

  override fun delete() = Unit

  override fun defaultValue(): T = state.value

  override fun changes(): Flow<T> = state

  override fun stateIn(scope: kotlinx.coroutines.CoroutineScope): StateFlow<T> = state
}
