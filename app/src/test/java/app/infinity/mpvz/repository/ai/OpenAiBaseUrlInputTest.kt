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
  fun acceptedValueIsWhatTheClientResolvesRequestsAgainst() {
    val stored = OpenAiBaseUrlInput.normalize("https://gateway.example/v1/")!!
    assertEquals("https://gateway.example/v1/chat/completions", OpenAiEndpoint.requestUrl(stored, "/chat/completions"))
  }
}
