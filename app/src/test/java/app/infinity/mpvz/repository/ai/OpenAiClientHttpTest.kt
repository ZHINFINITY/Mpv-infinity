/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.ai

import app.infinity.mpvz.preferences.AiPreferences
import app.infinity.mpvz.preferences.preference.Preference
import app.infinity.mpvz.preferences.preference.PreferenceStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenAiClientHttpTest {
  private lateinit var server: MockWebServer
  private lateinit var client: OpenAiClient

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    val baseUrl = server.url("/v1").toString().removeSuffix("/")
    client =
      OpenAiClient(
        OkHttpClient(),
        Json { ignoreUnknownKeys = true },
        AiPreferences(TestPreferenceStore(mapOf("ai_openai_base_url" to baseUrl))),
      )
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun fetchModelsUsesConfiguredPathAndBearerHeader() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(200).setBody("{\"data\":[]}"))

    client.fetchModels("custom-secret").getOrThrow()

    val request = server.takeRequest(2, TimeUnit.SECONDS)
    assertEquals("/v1/models", request!!.path)
    assertEquals("Bearer custom-secret", request.getHeader("Authorization"))
  }

  @Test
  fun generateContentUsesConfiguredPathAndBearerHeader() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"),
    )

    client.generateContent(
      apiKey = "custom-secret",
      model = "gpt-test",
      instruction = "system",
      userInput = "hello",
      options = AiGenerationOptions(),
    ).getOrThrow()

    val request = server.takeRequest(2, TimeUnit.SECONDS)
    assertEquals("/v1/chat/completions", request!!.path)
    assertEquals("Bearer custom-secret", request.getHeader("Authorization"))
  }
}

private class TestPreferenceStore(
  private val values: Map<String, String>,
) : PreferenceStore {
  override fun getString(key: String, defaultValue: String): Preference<String> =
    TestPreference(key, values[key] ?: defaultValue)

  override fun getLong(key: String, defaultValue: Long): Preference<Long> = TestPreference(key, defaultValue)

  override fun getInt(key: String, defaultValue: Int): Preference<Int> = TestPreference(key, defaultValue)

  override fun getFloat(key: String, defaultValue: Float): Preference<Float> = TestPreference(key, defaultValue)

  override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = TestPreference(key, defaultValue)

  override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
    TestPreference(key, defaultValue)

  override fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T> = TestPreference(key, defaultValue)

  override fun getAll(): Map<String, *> = values
}

private class TestPreference<T>(
  private val preferenceKey: String,
  initialValue: T,
) : Preference<T> {
  private val state = MutableStateFlow(initialValue)

  override fun key(): String = preferenceKey
  override fun get(): T = state.value
  override fun set(value: T) { state.value = value }
  override fun isSet(): Boolean = true
  override fun delete() = Unit
  override fun defaultValue(): T = state.value
  override fun changes(): Flow<T> = state
  override fun stateIn(scope: kotlinx.coroutines.CoroutineScope): StateFlow<T> = state
}
