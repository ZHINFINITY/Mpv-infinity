/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.repository.ai

import java.net.URISyntaxException

/** Validates the OpenAI-compatible base URL typed in AI Integration settings. */
internal object OpenAiBaseUrlInput {
  /**
   * Returns the value to store: "" restores the official endpoint, a trimmed URL is kept
   * as typed, and null means the input is rejected and the saved value must not change.
   */
  fun normalize(raw: String): String? {
    val configured = raw.trim()
    if (configured.isEmpty()) return ""
    return try {
      OpenAiEndpoint.requestUrl(configured, "/models")
      configured
    } catch (_: IllegalArgumentException) {
      null
    } catch (_: URISyntaxException) {
      null
    }
  }
}
