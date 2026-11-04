package app.infinity.mpvz.ui.player

/**
 * Direct audio endpoints can be extensionless (for example, Navidrome's stream.view URL).
 * Preserve yt-dlp for ambiguous webpage URLs, but do not send known audio media through it.
 */
internal object MpvYtdlpPolicy {
  fun requiresPageResolution(
    looksLikeWebSource: Boolean,
    isDefinitelyAudioOnly: Boolean,
    detectedAudioMimeType: String?,
  ): Boolean =
    looksLikeWebSource && !isDefinitelyAudioOnly && detectedAudioMimeType == null

  fun shouldDisableHook(
    isDefinitelyAudioOnly: Boolean,
    detectedAudioMimeType: String?,
  ): Boolean = isDefinitelyAudioOnly || detectedAudioMimeType != null
}
