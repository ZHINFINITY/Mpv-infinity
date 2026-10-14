/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.utils.media

object SharedUrlExtractor {
  private val webUrlPattern = Regex("""(?i)\bhttps?://[^\s<>\"'‘’“”]+""")
  private val simpleTrailingPunctuation =
    charArrayOf('.', ',', ';', ':', '!', '?', '…', '。', '，', '；', '：', '！', '？', '、')

  fun normalizeInput(text: String): String =
    (firstWebUrl(text) ?: text.trim()).replace(
      Regex("(?i)(https?://(?:www\\.)?instagram\\.com)/share/(reel|p|tv)/"),
      "$1/$2/",
    )

  fun firstWebUrl(text: String): String? =
    webUrlPattern
      .findAll(text)
      .map { match -> trimSharePunctuation(match.value) }
      .firstOrNull { url -> url.length > "https://".length }

  private fun trimSharePunctuation(rawUrl: String): String {
    var url = rawUrl.trimEnd(*simpleTrailingPunctuation)
    url = trimUnmatchedClosingDelimiter(url, '(', ')')
    url = trimUnmatchedClosingDelimiter(url, '[', ']')
    return trimUnmatchedClosingDelimiter(url, '{', '}')
  }

  private fun trimUnmatchedClosingDelimiter(
    value: String,
    opening: Char,
    closing: Char,
  ): String {
    var result = value
    while (result.endsWith(closing) && result.count { it == closing } > result.count { it == opening }) {
      result = result.dropLast(1)
    }
    return result
  }
}
