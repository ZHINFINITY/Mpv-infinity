package app.infinity.mpvz.domain.download

/** Pure filename policy shared by sidecar downloads and cleanup. */
internal object SidecarSubtitlePolicy {
  private val languageToken = Regex("[a-z]{2,3}(?:[-_][a-z0-9]{2,8})*|[\\u0370-\\u03ff]{2,}", RegexOption.IGNORE_CASE)
  private val roleToken = setOf("forced", "default", "sdh", "cc", "hi")

  fun videoBaseName(videoFileName: String): String = videoFileName.substringBeforeLast('.')

  fun sidecarFileName(
    videoFileName: String,
    languageCode: String?,
    label: String,
    forced: Boolean,
    index: Int,
    extension: String,
    occupiedNames: Set<String> = emptySet(),
  ): String {
    val base = videoBaseName(videoFileName)
    val token = sanitizeToken(languageCode?.takeIf { it.isNotBlank() } ?: "sub${index + 1}", label)
    val role = if (forced) ".forced" else ""
    val stem = "$base.$token$role"
    return "$stem.$extension"
  }

  /** True only for this video's exact basename and recognized sidecar suffix tokens. */
  fun belongsToVideo(videoFileName: String, candidateName: String, subtitleExtensions: Set<String>): Boolean {
    if (candidateName == videoFileName || candidateName.endsWith(".part", ignoreCase = true)) return false
    val extension = candidateName.substringAfterLast('.', "").lowercase()
    if (extension !in subtitleExtensions) return false
    val base = videoBaseName(videoFileName)
    val candidateBase = candidateName.substringBeforeLast('.')
    if (candidateBase == base) return true // video.srt
    val suffix = candidateBase.removePrefix("$base.")
    if (suffix == candidateBase) return false // no dot delimiter: Movie2.srt
    val tokens = suffix.split('.')
    if (tokens.isEmpty() || !languageToken.matches(tokens[0])) return false
    return when (tokens.size) {
      1 -> true
      2 -> tokens[1].lowercase() in roleToken
      else -> false
    }
  }

  private fun sanitizeToken(raw: String, label: String): String {
    // Keep the historical language/label sanitization, but make the no-language fallback
    // deterministic so cleanup can recognize it without accepting arbitrary suffixes.
    val source = if (raw.startsWith("sub")) raw else raw.ifBlank { label.ifBlank { "sub1" } }
    return DownloadLocations.sanitizeName(source).replace(' ', '_').take(24).ifBlank { "sub1" }
  }
}
