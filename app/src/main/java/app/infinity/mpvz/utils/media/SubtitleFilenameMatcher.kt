/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

/** Matches a subtitle sidecar to its video while allowing language/format suffixes. */
object SubtitleFilenameMatcher {
  private val languageSuffix = Regex(
    "^[a-z]{2,3}(?:[-_][a-z]{2,4})?(?:[._-](?:forced|default|sdh|cc|hi))?$",
  )
  private val namedLanguages = setOf("greek")

  fun matches(subtitleFileName: String, videoFileName: String): Boolean {
    val subtitleBase = subtitleFileName.substringBeforeLast('.', subtitleFileName).trim().lowercase()
    val videoBase = videoFileName.substringBeforeLast('.', videoFileName).trim().lowercase()
    if (subtitleBase.isBlank() || videoBase.isBlank()) return false
    if (subtitleBase == videoBase) return true

    val suffix = sequenceOf('.', '_', '-')
      .firstNotNullOfOrNull { separator ->
        val prefix = "$videoBase$separator"
        subtitleBase.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
      } ?: return false
    return suffix in namedLanguages || languageSuffix.matches(suffix)
  }
}
