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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientVerifyKeyResponseTest {
  @Test
  fun verifyKeyClosesSuccessfulResponsesForEveryProvider() = runBlocking {
    val closed = AtomicInteger(0)
    val clients = clients(closed, code = 200)

    clients.forEach { assertTrue(it.verifyKey("test-key").isSuccess) }

    assertTrue(closed.get() == 6)
  }

  @Test
  fun verifyKeyClosesFailedResponsesForEveryProvider() = runBlocking {
    val closed = AtomicInteger(0)
    val clients = clients(closed, code = 401)

    clients.forEach { assertFalse(it.verifyKey("test-key").isSuccess) }

    assertTrue(closed.get() == 6)
  }

  private fun clients(closed: AtomicInteger, code: Int): List<AiClient> {
    val http = OkHttpClient.Builder().addInterceptor(responseInterceptor(closed, code)).build()
    val json = Json { ignoreUnknownKeys = true }
    return listOf(
      OpenAiClient(http, json, AiPreferences(TestPreferenceStore(emptyMap()))),
      AnthropicClient(http, json),
      OpenRouterClient(http, json),
      TogetherClient(http, json),
      GroqClient(http, json),
      OpenCodeClient(http, json),
    )
  }

  private fun responseInterceptor(closed: AtomicInteger, code: Int) = Interceptor { chain ->
    val body = TrackingResponseBody(closed)
    Response.Builder()
      .request(chain.request())
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message("test")
      .body(body)
      .build()
  }
}

private class TrackingResponseBody(
  private val closed: AtomicInteger,
) : ResponseBody() {
  override fun contentType(): MediaType? = null
  override fun contentLength(): Long = 11
  override fun source(): BufferedSource = Buffer().writeUtf8("{\"data\":[]}").buffer()
  override fun close() {
    closed.incrementAndGet()
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
