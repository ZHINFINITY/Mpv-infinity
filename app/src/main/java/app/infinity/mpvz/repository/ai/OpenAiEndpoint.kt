/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.repository.ai

import java.net.URI

/**
 * Validates and joins an OpenAI-compatible API base URL.
 *
 * The base URL may contain a path (for example, `/v1`), which is preserved. Credentials,
 * query strings, fragments, and non-HTTP schemes are rejected so an API key can never be
 * accidentally sent to a URL supplied as part of the endpoint configuration. Explicit `http`
 * URLs are supported for local/self-hosted servers; Android's manifest permits cleartext traffic.
 */
object OpenAiEndpoint {
  const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

  fun requestUrl(baseUrl: String, route: String): String {
    require(route.startsWith("/")) { "OpenAI endpoint route must start with /" }
    val configured = baseUrl.trim()
    require(configured.isNotEmpty()) { "OpenAI API base URL must not be blank" }

    val uri = try {
      URI(configured)
    } catch (error: Exception) {
      throw IllegalArgumentException("Invalid OpenAI API base URL", error)
    }
    require(uri.isAbsolute && (uri.scheme.equals("https", true) || uri.scheme.equals("http", true))) {
      "OpenAI API base URL must use http or https"
    }
    require(!uri.host.isNullOrBlank()) { "OpenAI API base URL must include a host" }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
      "OpenAI API base URL must not contain credentials, query parameters, or fragments"
    }

    val path = uri.path.trimEnd('/')
    val joinedPath = "$path$route"
    return URI(uri.scheme, uri.userInfo, uri.host, uri.port, joinedPath, null, null).toString()
  }
}
