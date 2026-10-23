package app.infinity.mpvz.utils.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioM3uPlaylistPolicyTest {
  @Test fun recognizesLiveFlacRadioStations() {
    val result = M3UParseResult.Success("Classical radio", listOf(
      M3UPlaylistItem("https://radio.example/classical.flac", "Classical FM", duration = -1),
    ))
    assertTrue(RadioM3uPlaylistPolicy.isRadio(result))
  }

  @Test fun rejectsIptvEvenWhenNamedRadio() {
    val result = M3UParseResult.Success("Radio TV", listOf(
      M3UPlaylistItem("https://example/live.m3u8", "Radio news", duration = -1),
    ))
    assertFalse(RadioM3uPlaylistPolicy.isRadio(result))
  }

  @Test fun rejectsRecordedFlacWithoutRadioEvidence() {
    val result = M3UParseResult.Success("Recorded concert", listOf(
      M3UPlaylistItem("/music/concert.flac", "Live recording", duration = 240),
    ))
    assertFalse(RadioM3uPlaylistPolicy.isRadio(result))
  }
}
