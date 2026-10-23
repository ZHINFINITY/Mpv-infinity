/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiBaseUrlInputTest {
  @Test
  fun blankInputRestoresTheOfficialEndpoint() {
    assertEquals("", OpenAiBaseUrlInput.normalize(""))
    assertEquals("", OpenAiBaseUrlInput.normalize("   "))
  }

  @Test
  fun validHttpAndHttpsBaseUrlsAreKeptTrimmed() {
    assertEquals("https://gateway.example/v1", OpenAiBaseUrlInput.normalize("  https://gateway.example/v1 "))
    assertEquals("http://192.168.1.20:11434/v1/", OpenAiBaseUrlInput.normalize("http://192.168.1.20:11434/v1/"))
  }

  @Test
  fun unsafeOrMalformedBaseUrlsAreRejected() {
    listOf(
      "gateway.example/v1",
      "ftp://gateway.example/v1",
      "https://user:secret@gateway.example/v1",
      "https://gateway.example/v1?key=abc",
      "https://gateway.example/v1#frag",
      "https:///v1",
      "https://gate way.example/v1",
    ).forEach { assertNull(it, OpenAiBaseUrlInput.normalize(it)) }
  }

  @Test
  fun cleartextHttpIsRejectedForPublicHostsSoTheApiKeyIsNeverSentUnencrypted() {
    listOf(
      "http://api.example.com/v1",
      "http://8.8.8.8/v1",
      "http://172.32.0.1:8080/v1",
      "http://192.169.1.1/v1",
      "http://[2001:db8::1]/v1",
      "http://localhost.example.com/v1",
    ).forEach { assertNull(it, OpenAiBaseUrlInput.normalize(it)) }
  }

  @Test
  fun cleartextHttpIsAllowedForDeviceAndLocalNetworkServers() {
    listOf(
      "http://localhost:1234/v1",
      "http://127.0.0.1:8080/v1",
      "http://10.0.2.2:11434/v1",
      "http://172.20.1.1/v1",
      "http://[::1]:8080/v1",
      "http://lmstudio.local:1234/v1",
    ).forEach { assertEquals(it, it, OpenAiBaseUrlInput.normalize(it)) }
    assertEquals("https://8.8.8.8/v1", OpenAiBaseUrlInput.normalize("https://8.8.8.8/v1"))
  }

  @Test
  fun acceptedValueIsWhatTheClientResolvesRequestsAgainst() {
    val stored = OpenAiBaseUrlInput.normalize("https://gateway.example/v1/")!!
    assertEquals("https://gateway.example/v1/chat/completions", OpenAiEndpoint.requestUrl(stored, "/chat/completions"))
  }
}
