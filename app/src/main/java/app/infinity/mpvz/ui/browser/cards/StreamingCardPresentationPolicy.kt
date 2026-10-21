/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.browser.cards

import java.net.URI

/** Pure presentation rules for stream metadata; never exposes credentials, paths, or queries. */
object StreamingCardPresentationPolicy {
  private val validScheme = Regex("[a-z][a-z0-9+.-]*")

  fun safeSourceLabel(rawUrl: String, providerLabel: String? = null): String {
    providerLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val value = rawUrl.trim()
    val uri = runCatching { URI(value) }.getOrNull()
    val host = uri?.host?.trim()?.takeIf { it.isNotEmpty() }
    if (host != null) return host.removePrefix("www.")
    val scheme = uri?.scheme?.trim()?.lowercase()?.takeIf { validScheme.matches(it) }
    return scheme?.let { "$it stream" } ?: "Stream source"
  }
}
