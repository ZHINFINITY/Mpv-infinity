/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

/** Header parsing and serialization shared by every HTTP playback entry point. */
internal object PlaybackHttpHeaders {
  private val headerNamePattern = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

  fun fromFlatPairs(values: Array<String>?): Map<String, String> {
    if (values == null) return emptyMap()
    val headers = linkedMapOf<String, String>()
    values.asList().chunked(2).forEach { pair ->
      if (pair.size == 2) put(headers, pair[0], pair[1])
    }
    return headers
  }

  fun merge(vararg sources: Map<String, String>): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    sources.forEach { source -> source.forEach { (name, value) -> put(headers, name, value) } }
    return headers
  }

  fun withDefault(
    headers: Map<String, String>,
    name: String,
    value: String?,
  ): Map<String, String> {
    if (value.isNullOrBlank() || value(headers, name) != null) return headers
    return merge(headers, mapOf(name to value))
  }

  fun value(
    headers: Map<String, String>,
    name: String,
  ): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

  fun userAgent(headers: Map<String, String>): String? = value(headers, "User-Agent")

  fun toMpvHeaderFields(headers: Map<String, String>): String =
    headers.entries
      .filterNot { it.key.equals("User-Agent", ignoreCase = true) }
      .joinToString(",") { (name, value) -> "$name: ${escapeMpvListValue(value)}" }

  private fun put(
    target: MutableMap<String, String>,
    rawName: String,
    rawValue: String,
  ) {
    val name = rawName.trim()
    val value = rawValue.trim()
    if (!headerNamePattern.matches(name) || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return
    target.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { existing -> target.remove(existing) }
    target[name] = value
  }

  private fun escapeMpvListValue(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace(",", "\\,")
}
