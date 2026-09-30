package app.infinity.mpvz.ui.browser.audiobooks

internal enum class AudiobookArtworkSourceKind { COVER_URI, EMBEDDED_AUDIO }

internal data class AudiobookArtworkSource(
  val kind: AudiobookArtworkSourceKind,
  val uri: String,
  val mediaPath: String? = null,
)

/** Preserve stored cover metadata first, then fall back to the first track's embedded/sidecar artwork. */
internal fun audiobookArtworkSources(
  coverUri: String?,
  fallbackTrackUri: String?,
  fallbackTrackPath: String? = null,
): List<AudiobookArtworkSource> = buildList {
  coverUri?.trim()?.takeIf(String::isNotBlank)?.let { uri ->
    add(AudiobookArtworkSource(AudiobookArtworkSourceKind.COVER_URI, uri))
  }
  fallbackTrackUri?.trim()?.takeIf(String::isNotBlank)?.let { uri ->
    add(AudiobookArtworkSource(AudiobookArtworkSourceKind.EMBEDDED_AUDIO, uri, fallbackTrackPath))
  }
}
