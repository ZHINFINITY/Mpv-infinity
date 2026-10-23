package app.infinity.mpvz.ui.player

/** Pure rules for M3U radio presentation; no network lookup or metadata fabrication. */
object RadioMetadataPolicy {
  /** Durations above a day, or the common one-second probe placeholder, are not finite radio items. */
  const val MAX_FINITE_DURATION_SECONDS = 24 * 60 * 60
  data class Presentation(
    val title: String,
    val artist: String?,
    val artworkUri: String?,
  )

  fun isRadio(
    queueIsM3u: Boolean,
    itemIsAudio: Boolean,
    streamUri: String?,
    durationSeconds: Int?,
  ): Boolean =
    queueIsM3u && itemIsAudio && isHttpUrl(streamUri) &&
      (durationSeconds == null || durationSeconds <= 1 || durationSeconds > MAX_FINITE_DURATION_SECONDS)

  fun presentation(
    stationTitle: String?,
    streamTitle: String?,
    streamArtist: String?,
    icyTitle: String?,
    explicitArtwork: String?,
    metadataArtwork: String?,
  ): Presentation {
    val title = clean(streamTitle) ?: clean(icyTitle) ?: clean(stationTitle).orEmpty()
    return Presentation(
      title = title,
      artist = clean(streamArtist),
      artworkUri = clean(metadataArtwork)?.takeIf(::isHttpUrl) ?: clean(explicitArtwork)?.takeIf(::isHttpUrl),
    )
  }

  /** A combined live artist/title is useful when demuxers only publish media-title.
   * A mount name, URL or preserved station label is never promoted into track metadata.
   */
  fun trackFromMediaTitle(value: String?, stationTitle: String?): String? =
    clean(value)?.takeIf { it != clean(stationTitle) && it.contains(" - ") && !it.contains("://") && !isHttpUrl(it) }

  fun isHttpUrl(value: String?): Boolean =
    value?.trim()?.let {
      runCatching {
        val uri = java.net.URI(it)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
      }.getOrDefault(false)
    } == true

  private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
