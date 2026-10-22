package app.infinity.mpvz.ui.player

enum class TrackMetadataChipKind { LANGUAGE, CODEC, CHANNELS, FLAG }

enum class TrackMetadataFlag { DEFAULT, FORCED, SDH, EXTERNAL }

data class TrackMetadataChip(
  val kind: TrackMetadataChipKind,
  val value: String,
  val flag: TrackMetadataFlag? = null,
)

/** Pure, resource-free formatting used by both MPV and native track sheets. */
object TrackMetadataFormatter {
  fun chips(track: TrackNode): List<TrackMetadataChip> = buildList {
    track.effectiveLang?.trim()?.takeIf { it.isNotEmpty() }?.let {
      add(TrackMetadataChip(TrackMetadataChipKind.LANGUAGE, it.uppercase()))
    }
    codecLabel(track.codec)?.let { add(TrackMetadataChip(TrackMetadataChipKind.CODEC, it)) }
    channels(track)?.let { add(TrackMetadataChip(TrackMetadataChipKind.CHANNELS, it)) }
    if (track.default == true) add(flag(TrackMetadataFlag.DEFAULT, "track_metadata_default"))
    if (track.forced == true) add(flag(TrackMetadataFlag.FORCED, "track_metadata_forced"))
    if (track.hearingImpaired == true) add(flag(TrackMetadataFlag.SDH, "track_metadata_sdh"))
    if (track.external == true) add(flag(TrackMetadataFlag.EXTERNAL, "track_metadata_external"))
  }

  private fun flag(flag: TrackMetadataFlag, resourceKey: String) =
    TrackMetadataChip(TrackMetadataChipKind.FLAG, resourceKey, flag)

  private fun codecLabel(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (value.lowercase()) {
      "hdmv_pgs_subtitle", "application/pgs" -> "PGS"
      "subrip", "application/x-subrip" -> "SRT"
      "ass", "ssa", "text/x-ssa" -> "ASS"
      "webvtt", "text/vtt" -> "VTT"
      "dvd_subtitle" -> "VOBSUB"
      "eac3", "audio/eac3" -> "EAC3"
      "ac3" -> "AC3"
      "aac", "audio/mp4a-latm" -> "AAC"
      "truehd" -> "TRUEHD"
      "dts" -> "DTS"
      "opus" -> "OPUS"
      "flac" -> "FLAC"
      "mp3", "audio/mpeg" -> "MP3"
      else -> value.uppercase()
    }
  }

  private fun channels(track: TrackNode): String? {
    track.demuxChannels?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val count = track.demuxChannelCount?.takeIf { it > 0 } ?: track.audioChannels?.takeIf { it > 0 } ?: return null
    return when (count) {
      1L -> "Mono"
      2L -> "2.0"
      6L -> "5.1"
      8L -> "7.1"
      else -> "$count ch"
    }
  }
}
