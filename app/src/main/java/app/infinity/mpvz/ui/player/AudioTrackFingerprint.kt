package app.infinity.mpvz.ui.player

import java.util.Locale

/** Stable audio identity used only when remapping a Native track to MPV after an engine handoff. */
internal data class AudioTrackFingerprint(
  val language: String? = null,
  val label: String? = null,
  val codec: String? = null,
  val mimeType: String? = null,
  val channelCount: Int? = null,
)

internal fun NativeTrack.toAudioTrackFingerprint(): AudioTrackFingerprint =
  AudioTrackFingerprint(
    language = language,
    label = label,
    codec = codecs,
    mimeType = sampleMimeType,
    channelCount = channelCount.takeIf { it > 0 },
  )

/** Conservative cross-engine matching: ambiguous matches are rejected instead of choosing track 0. */
internal object AudioTrackSelectionMatcher {
  fun findBestMatchIndex(
    target: AudioTrackFingerprint,
    candidates: List<AudioTrackFingerprint>,
  ): Int? {
    val targetLanguage = normalizeLanguage(target.language)
    val targetLabel = normalizeLabel(target.label)
    val targetCodec = normalizeCodec(target.codec, target.mimeType)
    val targetChannels = target.channelCount?.takeIf { it > 0 }

    val ranked = candidates.mapIndexedNotNull { index, candidate ->
      val candidateLanguage = normalizeLanguage(candidate.language)
      val candidateLabel = normalizeLabel(candidate.label)
      val candidateCodec = normalizeCodec(candidate.codec, candidate.mimeType)
      val candidateChannels = candidate.channelCount?.takeIf { it > 0 }

      if (targetLanguage != null && candidateLanguage != null && targetLanguage != candidateLanguage) {
        return@mapIndexedNotNull null
      }
      if (targetCodec != null && candidateCodec != null && targetCodec != candidateCodec) {
        return@mapIndexedNotNull null
      }
      if (targetChannels != null && candidateChannels != null && targetChannels != candidateChannels) {
        return@mapIndexedNotNull null
      }

      var score = 0
      if (targetLanguage != null && candidateLanguage == targetLanguage) score += 100
      if (targetLabel != null && candidateLabel == targetLabel) score += 100
      if (targetCodec != null && candidateCodec == targetCodec) score += 20
      if (targetChannels != null && candidateChannels == targetChannels) score += 10
      if (score >= 100) index to score else null
    }
    val bestScore = ranked.maxOfOrNull { it.second } ?: return null
    val bestMatches = ranked.filter { it.second == bestScore }
    return bestMatches.singleOrNull()?.first
  }

  private fun normalizeLanguage(value: String?): String? {
    val language = value
      ?.trim()
      ?.lowercase(Locale.ROOT)
      ?.substringBefore('-')
      ?.substringBefore('_')
      ?.takeIf { it.isNotEmpty() && it !in setOf("und", "unknown", "zxx") }
      ?: return null
    return when (language) {
      "en" -> "eng"
      "ja" -> "jpn"
      "zh", "chi", "cmn" -> "zho"
      else -> if (language.length == 2) {
        runCatching { Locale(language).isO3Language.lowercase(Locale.ROOT) }
          .getOrNull()
          ?.takeIf { it.isNotBlank() }
          ?: language
      } else {
        language
      }
    }
  }

  private fun normalizeLabel(value: String?): String? =
    value
      ?.lowercase(Locale.ROOT)
      ?.replace(Regex("[^a-z0-9]+"), " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  private fun normalizeCodec(codec: String?, mimeType: String?): String? {
    val value = listOfNotNull(codec, mimeType)
      .joinToString(" ")
      .lowercase(Locale.ROOT)
      .replace('_', '-')
    if (value.isBlank()) return null
    return when {
      "dts" in value || "dca" in value -> "dts"
      "e-ac-3" in value || "eac3" in value -> "eac3"
      "ac-3" in value || Regex("\\bac3\\b").containsMatchIn(value) -> "ac3"
      "truehd" in value -> "truehd"
      "flac" in value -> "flac"
      "aac" in value -> "aac"
      "opus" in value -> "opus"
      "vorbis" in value -> "vorbis"
      "pcm" in value -> "pcm"
      else -> value
    }
  }
}
