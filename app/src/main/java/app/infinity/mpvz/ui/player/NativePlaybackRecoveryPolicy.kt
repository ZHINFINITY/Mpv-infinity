package app.infinity.mpvz.ui.player

/** Media3 errors MPV can often recover from with a different demuxer/decoder. */
internal object NativePlaybackRecoveryPolicy {
  fun shouldFallbackToMpv(errorCodeName: String, causeClassNames: Iterable<String>): Boolean {
    if (errorCodeName.contains("DECOD", ignoreCase = true) ||
      errorCodeName.contains("PARSING_CONTAINER_UNSUPPORTED", ignoreCase = true) ||
      errorCodeName.contains("PARSING_MANIFEST_UNSUPPORTED", ignoreCase = true)
    ) return true

    return causeClassNames.any { className ->
      className.contains("DecoderInitializationException", ignoreCase = true) ||
        className.contains("DecoderQueryException", ignoreCase = true) ||
        className.contains("UnrecognizedInputFormatException", ignoreCase = true)
    }
  }
}
