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
import java.util.concurrent.atomic.AtomicBoolean
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
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientVerifyKeyResponseTest {
  @Test
  fun verifyKeyClosesSuccessfulResponsesForEveryProvider() = runBlocking {
    val closed = AtomicInteger(0)
    clients(closed, 200).forEach { client ->
      assertTrue(client.javaClass.simpleName, client.verifyKey("test-key").isSuccess)
    }
    assertTrue("Each response source must close exactly once", closed.get() == 6)
  }

  @Test
  fun verifyKeyClosesFailedResponsesForEveryProvider() = runBlocking {
    val closed = AtomicInteger(0)
    clients(closed, 401).forEach { client ->
      assertFalse(client.javaClass.simpleName, client.verifyKey("test-key").isSuccess)
    }
    assertTrue("Each response source must close exactly once", closed.get() == 6)
  }

  private fun clients(closed: AtomicInteger, code: Int): List<AiClient> {
    val http = OkHttpClient.Builder().addInterceptor(responseInterceptor(closed, code)).build()
    val json = Json { ignoreUnknownKeys = true }
    return listOf(
      OpenAiClient(http, json, AiPreferences(VerificationPreferenceStore())),
      AnthropicClient(http, json),
      OpenRouterClient(http, json),
      TogetherClient(http, json),
      GroqClient(http, json),
      OpenCodeClient(http, json),
    )
  }

  private fun responseInterceptor(closed: AtomicInteger, code: Int) = Interceptor { chain ->
    Response.Builder()
      .request(chain.request())
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message("test")
      .body(VerificationResponseBody(closed))
      .build()
  }
}

private class VerificationResponseBody(closed: AtomicInteger) : ResponseBody() {
  private val bytes = "{\"data\":[]}".toByteArray()
  private val wasClosed = AtomicBoolean(false)
  private val trackedSource = object : ForwardingSource(Buffer().write(bytes)) {
    override fun close() {
      if (wasClosed.compareAndSet(false, true)) closed.incrementAndGet()
      super.close()
    }
  }.buffer()

  override fun contentType(): MediaType? = null
  override fun contentLength(): Long = bytes.size.toLong()
  override fun source(): BufferedSource = trackedSource
}

private class VerificationPreferenceStore : PreferenceStore {
  override fun getString(key: String, defaultValue: String): Preference<String> =
    VerificationPreference(key, defaultValue)
  override fun getLong(key: String, defaultValue: Long): Preference<Long> = VerificationPreference(key, defaultValue)
  override fun getInt(key: String, defaultValue: Int): Preference<Int> = VerificationPreference(key, defaultValue)
  override fun getFloat(key: String, defaultValue: Float): Preference<Float> = VerificationPreference(key, defaultValue)
  override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = VerificationPreference(key, defaultValue)
  override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
    VerificationPreference(key, defaultValue)
  override fun <T> getObject(
    key: String,
    defaultValue: T,
    serializer: (T) -> String,
    deserializer: (String) -> T,
  ): Preference<T> = VerificationPreference(key, defaultValue)
  override fun getAll(): Map<String, *> = emptyMap<String, Any>()
}

private class VerificationPreference<T>(
  private val preferenceKey: String,
  private val initialValue: T,
) : Preference<T> {
  private val state = MutableStateFlow(initialValue)
  override fun key(): String = preferenceKey
  override fun get(): T = state.value
  override fun set(value: T) { state.value = value }
  override fun isSet(): Boolean = true
  override fun delete() = Unit
  override fun defaultValue(): T = initialValue
  override fun changes(): Flow<T> = state
  override fun stateIn(scope: kotlinx.coroutines.CoroutineScope): StateFlow<T> = state
}
