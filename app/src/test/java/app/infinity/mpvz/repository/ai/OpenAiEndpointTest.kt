/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiEndpointTest {
  @Test
  fun joinsRoutesWithoutDuplicatingSlashesAndPreservesVersionPath() {
    assertEquals(
      "https://gateway.example/v1/chat/completions",
      OpenAiEndpoint.requestUrl("https://gateway.example/v1/", "/chat/completions"),
    )
    assertEquals(
      "https://gateway.example/models",
      OpenAiEndpoint.requestUrl("https://gateway.example", "/models"),
    )
  }

  @Test
  fun permitsExplicitLocalHttpEndpoint() {
    assertEquals(
      "http://192.168.1.20:8080/v1/models",
      OpenAiEndpoint.requestUrl("http://192.168.1.20:8080/v1", "/models"),
    )
  }

  @Test
  fun rejectsInvalidOrCredentialBearingEndpoints() {
    listOf(
      "",
      "api.example/v1",
      "ftp://gateway.example/v1",
      "https:///v1",
      "https://user:secret@gateway.example/v1",
      "https://gateway.example/v1?key=secret",
      "https://gateway.example/v1#fragment",
    ).forEach { endpoint ->
      try {
        OpenAiEndpoint.requestUrl(endpoint, "/models")
        throw AssertionError("Expected endpoint to be rejected: $endpoint")
      } catch (_: IllegalArgumentException) {
        // Expected.
      }
    }
  }

  @Test
  fun rejectsRoutesThatCouldEscapeTheConfiguredBase() {
    try {
      OpenAiEndpoint.requestUrl("https://gateway.example/v1", "models")
      throw AssertionError("Expected route without a leading slash to be rejected")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
  }
}
