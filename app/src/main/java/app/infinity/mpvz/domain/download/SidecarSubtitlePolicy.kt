/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.domain.download

import java.util.Locale

/** Pure filename policy shared by sidecar downloads and cleanup. */
internal object SidecarSubtitlePolicy {
  private val languageToken = Regex("[a-z]{2,3}(?:[-_][a-z]{2,4})?", RegexOption.IGNORE_CASE)
  private val roleTokens = setOf("forced", "default", "sdh", "cc", "hi")

  fun sidecarFileName(
    videoFileName: String,
    languageCode: String?,
    label: String,
    forced: Boolean,
    extension: String,
  ): String {
    val raw = languageCode?.trim().orEmpty().lowercase(Locale.ROOT)
    val language = when {
      languageToken.matches(raw) -> raw
      raw == "greek" || label.trim().equals("Greek", ignoreCase = true) -> "el"
      label.trim().equals("English", ignoreCase = true) -> "en"
      else -> "und"
    }
    val role = if (forced) ".forced" else ""
    return "${videoFileName.substringBeforeLast('.')}.$language$role.$extension"
  }

  /** Prefer leaving an ambiguous legacy file behind to deleting another video's subtitles. */
  fun belongsToVideo(videoFileName: String, candidateName: String, subtitleExtensions: Set<String>): Boolean {
    if (candidateName == videoFileName || candidateName.endsWith(".part", ignoreCase = true)) return false
    val extension = candidateName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension !in subtitleExtensions) return false
    val base = videoFileName.substringBeforeLast('.')
    val candidateBase = candidateName.substringBeforeLast('.')
    if (candidateBase == base) return true
    if (!candidateBase.startsWith("$base.")) return false
    val tokens = candidateBase.removePrefix("$base.").split('.')
    val language = tokens[0]
    if (!languageToken.matches(language) && !language.equals("greek", ignoreCase = true)) return false
    return tokens.size == 1 || (tokens.size == 2 && tokens[1].lowercase(Locale.ROOT) in roleTokens)
  }

  fun canDeleteForVideo(
    videoFileName: String,
    candidateName: String,
    subtitleExtensions: Set<String>,
    otherFileNames: Collection<String>,
  ): Boolean {
    if (!belongsToVideo(videoFileName, candidateName, subtitleExtensions)) return false
    val candidateBase = candidateName.substringBeforeLast('.')
    return otherFileNames.none { filename ->
      val otherName = filename.removeSuffix(".part")
      val otherBase = otherName.substringBeforeLast('.')
      otherName != videoFileName &&
        otherName.substringAfterLast('.', "").lowercase(Locale.ROOT) !in subtitleExtensions &&
        otherBase.isNotBlank() &&
        (candidateBase == otherBase || candidateBase.startsWith("$otherBase."))
    }
  }
}
