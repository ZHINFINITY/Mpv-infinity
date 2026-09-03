/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.data.lyrics

data class SupportedLanguage(
  val code: String,
  val displayName: String,
  val isRomanization: Boolean = false,
  val subtitle: String? = null,
)

object LyricsLanguageOptions {
  val ALL_LANGUAGES: List<SupportedLanguage> =
    listOf(
      SupportedLanguage("en", "English"),
      SupportedLanguage("ja", "Japanese"),
      SupportedLanguage("ko", "Korean"),
      SupportedLanguage("zh", "Chinese"),
    )

  fun getDisplayName(code: String): String =
    ALL_LANGUAGES.find { it.code == code }?.displayName ?: code
}
