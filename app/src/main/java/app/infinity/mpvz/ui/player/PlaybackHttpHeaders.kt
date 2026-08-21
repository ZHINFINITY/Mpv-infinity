/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

  /**
   * Reads the network-header options that are useful for URL playback from mpv.conf.
   *
   * mpv accepts `referrer=` as an option, while Android HTTP clients expect the standard
   * `Referer` request-header name. Keep this conversion here so MPV and Media3 receive the
   * same canonical header map. Malformed or unsupported config lines are ignored.
   */
  fun fromMpvConf(content: String?): Map<String, String> {
    if (content.isNullOrBlank()) return emptyMap()
    val headers = linkedMapOf<String, String>()
    content.lineSequence().forEach { rawLine ->
      val line = rawLine.trim()
      if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) return@forEach
      val separator = line.indexOf('=')
      if (separator <= 0) return@forEach
      val option = line.substring(0, separator).trim().lowercase()
      val rawValue = line.substring(separator + 1).trim()
      val value = unquote(rawValue)
      if (value.isBlank()) return@forEach
      when (option) {
        "referrer", "referer" -> put(headers, "Referer", value)
        "http-header-fields", "http-header-fields+" -> parseHeaderFields(value).forEach { (name, headerValue) ->
          put(headers, name, headerValue)
        }
      }
    }
    return headers
  }

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

  private fun parseHeaderFields(value: String): Map<String, String> =
    value
      .split(Regex("(?<!\\\\),"))
      .mapNotNull { field ->
        val separator = field.indexOf(':')
        if (separator <= 0) return@mapNotNull null
        field.substring(0, separator).trim() to field.substring(separator + 1).trim()
      }
      .toMap()

  private fun unquote(value: String): String =
    value
      .removeSurrounding("\"", "\"")
      .removeSurrounding("'", "'")
      .trim()

  private fun escapeMpvListValue(value: String): String =
    value
      .replace("\\", "\\\\")
      .replace(",", "\\,")
}
