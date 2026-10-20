package app.infinity.mpvz.ui.player

internal enum class NativePlaybackFallbackKind(val toastMessage: String) {
  AUDIO_DECODER("Native could not decode the selected audio track; switching to MPV."),
  VIDEO_DECODER("Native could not decode this video; switching to MPV."),
  UNSUPPORTED_MEDIA("Native could not read this media; switching to MPV."),
  GENERIC_DECODER("Native could not decode this media; switching to MPV."),
}

/** Media3 errors MPV can often recover from with a different demuxer/decoder. */
internal object NativePlaybackRecoveryPolicy {
  fun fallbackKind(
    errorCodeName: String,
    causeClassNames: Iterable<String>,
    rendererName: String? = null,
    rendererMimeType: String? = null,
  ): NativePlaybackFallbackKind? {
    val causes = causeClassNames.toList()
    val normalizedCode = errorCodeName.uppercase()
    val normalizedRenderer = rendererName.orEmpty().lowercase()
    val normalizedMimeType = rendererMimeType.orEmpty().lowercase()

    if (causes.any { it.contains("Media3FlowSdrOutputException", ignoreCase = true) }) {
      return NativePlaybackFallbackKind.VIDEO_DECODER
    }
    if (
      normalizedCode.contains("PARSING_CONTAINER_UNSUPPORTED") ||
      normalizedCode.contains("PARSING_MANIFEST_UNSUPPORTED")
    ) {
      return NativePlaybackFallbackKind.UNSUPPORTED_MEDIA
    }

    val decoderFailure = normalizedCode.contains("DECOD") || causes.any { className ->
      className.contains("DecoderInitializationException", ignoreCase = true) ||
        className.contains("DecoderQueryException", ignoreCase = true) ||
        className.contains("UnrecognizedInputFormatException", ignoreCase = true)
    }
    if (!decoderFailure) return null

    return when {
      normalizedMimeType.startsWith("audio/") || normalizedRenderer.contains("audio") ->
        NativePlaybackFallbackKind.AUDIO_DECODER
      normalizedMimeType.startsWith("video/") || normalizedRenderer.contains("video") ->
        NativePlaybackFallbackKind.VIDEO_DECODER
      else -> NativePlaybackFallbackKind.GENERIC_DECODER
    }
  }

  fun shouldFallbackToMpv(
    errorCodeName: String,
    causeClassNames: Iterable<String>,
  ): Boolean = fallbackKind(errorCodeName, causeClassNames) != null
}
