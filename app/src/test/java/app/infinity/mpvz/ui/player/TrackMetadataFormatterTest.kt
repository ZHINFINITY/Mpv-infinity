package app.infinity.mpvz.ui.player

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class TrackMetadataFormatterTest {
  @Test fun mpvStyleInputProducesOrderedChips() {
    val chips = TrackMetadataFormatter.chips(TrackNode(1, "audio", lang = "eng", codec = "eac3", demuxChannels = "5.1(side)", default = true, forced = true, hearingImpaired = true, external = true))
    assertEquals(listOf("ENG", "EAC3", "5.1(side)", "track_metadata_default", "track_metadata_forced", "track_metadata_sdh", "track_metadata_external"), chips.map { it.value })
  }

  @Test fun nativeStyleMimeAndChannelCountAreMapped() {
    val chips = TrackMetadataFormatter.chips(TrackNode(2, "audio", lang = "fr", codec = "audio/mp4a-latm", demuxChannelCount = 6))
    assertEquals(listOf("FR", "AAC", "5.1"), chips.map { it.value })
  }

  @Test fun blanksAndUnknownCodecAreHandled() {
    val chips = TrackMetadataFormatter.chips(TrackNode(3, "sub", lang = " ", codec = "x-custom", demuxChannels = " "))
    assertEquals(listOf("X-CUSTOM"), chips.map { it.value })
    assertTrue(chips.none { it.kind == TrackMetadataChipKind.FLAG })
  }

  @Test fun channelMappingCoversCommonAndFallbackCounts() {
    fun channel(count: Long) = TrackMetadataFormatter.chips(TrackNode(1, "audio", demuxChannelCount = count)).single().value
    assertEquals("Mono", channel(1)); assertEquals("2.0", channel(2)); assertEquals("5.1", channel(6)); assertEquals("7.1", channel(8)); assertEquals("4 ch", channel(4))
  }
}
