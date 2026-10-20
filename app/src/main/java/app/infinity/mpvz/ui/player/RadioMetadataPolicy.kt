package app.infinity.mpvz.ui.player

/** Pure rules for M3U radio presentation; no network lookup or metadata fabrication. */
object RadioMetadataPolicy {
  /** Durations above a day are demuxer clock/epoch values, not finite radio items. */
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
      (durationSeconds == null || durationSeconds <= 0 || durationSeconds > MAX_FINITE_DURATION_SECONDS)

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
      artworkUri = clean(explicitArtwork)?.takeIf(::isHttpUrl) ?: clean(metadataArtwork)?.takeIf(::isHttpUrl),
    )
  }

  fun isHttpUrl(value: String?): Boolean =
    value?.trim()?.let { it.startsWith("http://", true) || it.startsWith("https://", true) } == true

  private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
