package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioMetadataPolicyTest {
  @Test fun identifiesOnlyUnknownDurationHttpM3uAudio() {
    assertTrue(RadioMetadataPolicy.isRadio(true, true, "https://radio.example/live", null))
    assertFalse(RadioMetadataPolicy.isRadio(false, true, "https://radio.example/live", null))
    assertFalse(RadioMetadataPolicy.isRadio(true, false, "https://radio.example/live", null))
    assertFalse(RadioMetadataPolicy.isRadio(true, true, "file:///music.mp3", null))
    assertFalse(RadioMetadataPolicy.isRadio(true, true, "https://radio.example/live", 180))
    assertTrue(RadioMetadataPolicy.isRadio(true, true, "https://radio.example/live", 1_792_529_064))
  }

  @Test fun streamMetadataWinsAndStationIsFallbackWithoutInventingArtist() {
    val result = RadioMetadataPolicy.presentation("Station", "Now playing", null, null, null, null)
    assertEquals("Now playing", result.title)
    assertNull(result.artist)

    val icy = RadioMetadataPolicy.presentation("Station", null, null, "Icy song", null, null)
    assertEquals("Icy song", icy.title)
    assertEquals("Station", RadioMetadataPolicy.presentation("Station", null, null, null, null, null).title)
  }

  @Test fun explicitArtworkWinsAndNonHttpArtworkIsRejected() {
    val explicit = RadioMetadataPolicy.presentation("Station", null, null, null, "https://station/logo", "https://stream/art")
    assertEquals("https://station/logo", explicit.artworkUri)
    val metadata = RadioMetadataPolicy.presentation("Station", null, null, null, null, "http://stream/art")
    assertEquals("http://stream/art", metadata.artworkUri)
    assertNull(RadioMetadataPolicy.presentation("Station", null, null, null, "file:///logo", "data:image/png;base64,x").artworkUri)
    assertFalse(RadioMetadataPolicy.isHttpUrl("http:///missing-host"))
    assertFalse(RadioMetadataPolicy.isHttpUrl("https://broken host/art"))
    assertTrue(RadioMetadataPolicy.isHttpUrl("HTTPS://radio.example/cover.jpg"))
  }
  @Test fun genuineCombinedMediaTitlePreservesApostrophes() {
    val title = "R.L. Burnside - It's Bad You Know"
    assertEquals(title, RadioMetadataPolicy.trackFromMediaTitle(title, "Radio Paradise"))
  }
  @Test fun mountNamesUrlsAndStationLabelsAreNotTrackMetadata() {
    assertNull(RadioMetadataPolicy.trackFromMediaTitle("flacm", "Radio Paradise"))
    assertNull(RadioMetadataPolicy.trackFromMediaTitle("Radio Paradise - Main", "Radio Paradise - Main"))
    assertNull(RadioMetadataPolicy.trackFromMediaTitle("https://radio.example/Artist - Song", "Station"))
    assertNull(RadioMetadataPolicy.trackFromMediaTitle(null, "Station"))
  }
}
