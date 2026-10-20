package app.infinity.mpvz.ui.player

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class TrackSelectionMetadata(
  val language: String? = null,
  val label: String? = null,
  val codec: String? = null,
  val mimeType: String? = null,
  val channelCount: Int? = null,
  val externalUri: String? = null,
)

@Serializable
enum class TrackSelectionState { UNKNOWN, DISABLED, SELECTED }

@Serializable
data class TrackSelectionFingerprint(
  val language: String? = null,
  val label: String? = null,
  val codec: String? = null,
  val mimeType: String? = null,
  val channelCount: Int? = null,
  val externalUri: String? = null,
  val ordinal: Int? = null,
) {
  val channels: Int? get() = channelCount

  companion object {
    fun capture(metadata: TrackSelectionMetadata?, state: TrackSelectionState, ordinal: Int? = null) =
      if (state == TrackSelectionState.SELECTED && metadata != null) {
        TrackSelectionFingerprint(
          metadata.language,
          metadata.label,
          metadata.codec,
          metadata.mimeType,
          metadata.channelCount?.takeIf { it > 0 },
          metadata.externalUri,
          ordinal,
        )
      } else null

    fun capture(
      language: String?,
      label: String?,
      codec: String?,
      channels: Int?,
      externalUri: String? = null,
      state: TrackSelectionState = TrackSelectionState.SELECTED,
      ordinal: Int? = null,
    ) = capture(TrackSelectionMetadata(language, label, codec, null, channels, externalUri), state, ordinal)
  }
}

@Serializable
data class SharedTrackSelection(
  val state: TrackSelectionState = TrackSelectionState.UNKNOWN,
  val fingerprint: TrackSelectionFingerprint? = null,
) {
  companion object {
    val Unknown = SharedTrackSelection()
    val Disabled = SharedTrackSelection(TrackSelectionState.DISABLED)
    fun selected(fingerprint: TrackSelectionFingerprint) =
      SharedTrackSelection(TrackSelectionState.SELECTED, fingerprint)
  }
}

typealias TrackSelection = SharedTrackSelection

object TrackSelectionMatcher {
  /** -1 means disabled; null means unknown or no safe match. */
  fun findMatchIndex(selection: SharedTrackSelection, candidates: List<TrackSelectionMetadata>): Int? {
    if (selection.state == TrackSelectionState.UNKNOWN) return null
    if (selection.state == TrackSelectionState.DISABLED) return -1
    val target = selection.fingerprint ?: return null
    val matches = candidates.mapIndexedNotNull { index, candidate ->
      if (matchesDescriptors(target, candidate)) index else null
    }
    if (matches.size == 1) return matches.single()
    // An ordinal is only a tie-breaker after the candidate passed semantic validation.
    return target.ordinal?.let { ordinal -> matches.singleOrNull { it == ordinal } }
  }

  private fun matchesDescriptors(target: TrackSelectionFingerprint, candidate: TrackSelectionMetadata): Boolean {
    // Embedded and external tracks are different identities. In particular, never fall
    // back to an embedded candidate when a persisted external URI is unavailable.
    if (target.externalUri != null || candidate.externalUri != null) {
      if (target.externalUri == null || target.externalUri != candidate.externalUri) return false
    }

    if (contradicts(target.language, candidate.language, ::normalizeLanguage)) return false
    if (contradicts(target.label, candidate.label, ::normalizeText)) return false
    if (contradicts(target.codec, candidate.codec, ::normalizeCodec, candidate.mimeType, codecField = true)) return false
    if (contradicts(target.mimeType, candidate.mimeType, ::normalizeMime)) return false
    if (target.channelCount != null && candidate.channelCount != null &&
      target.channelCount != candidate.channelCount) return false

    // A missing field is not a contradiction. Still, metadata must provide positive
    // semantic evidence; channel count or MIME alone is too weak to select a track.
    return sameNonBlank(target.language, candidate.language, ::normalizeLanguage) ||
      sameNonBlank(target.label, candidate.label, ::normalizeText) ||
      target.externalUri != null ||
      comparableCodec(target, candidate)
  }

  private fun contradicts(
    target: String?, candidate: String?, normalize: (String?) -> String?, candidateMime: String? = null,
    codecField: Boolean = false,
  ): Boolean {
    if (target.isNullOrBlank() || candidate.isNullOrBlank()) return false
    val left = normalize(target) ?: return false
    val right = normalize(candidate) ?: return false
    // For codecs, a MIME-derived canonical value can validate a differently named codec.
    if (codecField && candidate.isNullOrBlank() && normalizeCodec(candidateMime) == left) return false
    return left != right
  }

  private fun sameNonBlank(a: String?, b: String?, normalize: (String?) -> String?) =
    !a.isNullOrBlank() && !b.isNullOrBlank() && normalize(a) != null && normalize(a) == normalize(b)

  private fun comparableCodec(target: TrackSelectionFingerprint, candidate: TrackSelectionMetadata): Boolean {
    val targetCodec = normalizeCodec(target.codec) ?: normalizeCodec(target.mimeType)
    val candidateCodec = normalizeCodec(candidate.codec) ?: normalizeCodec(candidate.mimeType)
    return targetCodec != null && targetCodec == candidateCodec
  }

  private fun normalizeLanguage(value: String?): String? {
    val token = value?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
      ?.takeIf { it.isNotEmpty() } ?: return null
    if (token.length == 2) return Locale.forLanguageTag(token).language.takeIf { it.isNotEmpty() }
    if (token.length == 3) {
      Locale.getAvailableLocales().firstOrNull { locale ->
        runCatching { locale.getISO3Language().equals(token, ignoreCase = true) }.getOrDefault(false)
      }?.language?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return token
  }

  private fun normalizeText(value: String?) = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

  private fun normalizeMime(value: String?): String? = normalizeCodec(value) ?: normalizeText(value)

  private fun normalizeCodec(value: String?): String? {
    val token = normalizeText(value)?.replace('_', '-') ?: return null
    return when {
      token == "aac" || token.startsWith("mp4a") || token == "audio/aac" || token == "audio/mp4" -> "aac"
      token == "mp3" || token == "mpeg" || token == "audio/mpeg" -> "mp3"
      token == "opus" || token == "audio/opus" -> "opus"
      token == "vorbis" || token == "audio/vorbis" || token == "audio/ogg" -> "vorbis"
      token == "flac" || token == "audio/flac" -> "flac"
      token == "ac3" || token == "audio/ac3" -> "ac3"
      token == "eac3" || token == "ec-3" || token == "audio/eac3" -> "eac3"
      token == "srt" || token == "subrip" || token == "text/srt" || token == "application/x-subrip" -> "subrip"
      token == "ass" || token == "ssa" || token == "text/x-ass" || token == "text/x-ssa" -> "ass"
      token == "vtt" || token == "webvtt" || token == "text/vtt" -> "webvtt"
      else -> token
    }
  }
}
