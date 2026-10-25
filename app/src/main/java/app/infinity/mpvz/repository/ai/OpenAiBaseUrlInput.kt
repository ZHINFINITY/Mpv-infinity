/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import java.net.URI
import java.net.URISyntaxException

/** Validates the OpenAI-compatible base URL typed in AI Integration settings. */
internal object OpenAiBaseUrlInput {
  /**
   * Returns the value to store: "" restores the official endpoint, a trimmed URL is kept
   * as typed, and null means the input is rejected and the saved value must not change.
   *
   * The client sends the API key as a Bearer header to this URL, so cleartext `http://`
   * is accepted only for hosts on the device or the local network (local model servers).
   */
  fun normalize(raw: String): String? {
    val configured = raw.trim()
    if (configured.isEmpty()) return ""
    return try {
      OpenAiEndpoint.requestUrl(configured, "/models")
      val uri = URI(configured)
      if (uri.scheme.equals("http", ignoreCase = true) && !isLocalHost(uri.host.orEmpty())) {
        null
      } else {
        configured
      }
    } catch (_: IllegalArgumentException) {
      null
    } catch (_: URISyntaxException) {
      null
    }
  }

  /** Literal-only check (no DNS): loopback, RFC 1918, link-local, IPv6 ULA, `localhost`, `.local`. */
  internal fun isLocalHost(rawHost: String): Boolean {
    val host =
      rawHost
        .trim()
        .removePrefix("[")
        .removeSuffix("]")
        .lowercase()
    if (host.isEmpty()) return false
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    if (host.contains(':')) {
      return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
    }
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets =
      parts.map { part ->
        if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return false
        part.toInt().also { if (it > 255) return false }
      }
    val (a, b) = octets
    return a == 127 ||
      a == 10 ||
      (a == 172 && b in 16..31) ||
      (a == 192 && b == 168) ||
      (a == 169 && b == 254)
  }
}
