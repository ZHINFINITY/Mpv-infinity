package app.infinity.mpvz.ui.browser.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSourceAudioFormatPolicyTest {
  @Test
  fun flacHeaderUsesSourceBitDepthAndSourceSampleRate() {
    val sourceFormat = MusicSourceAudioFormatPolicy.fromSourceMetadata("24 bits", "96.0 kHz")

    assertEquals(24, sourceFormat?.bitDepth)
    assertEquals(96_000, sourceFormat?.sampleRateHz)
    assertEquals("LOSSLESS · 24-bit · 96 kHz", MusicSourceAudioFormatPolicy.headerLabel("FLAC", sourceFormat))
  }

  @Test
  fun absentSourceMetadataDoesNotInventDecodedBitDepthOrSampleRate() {
    val sourceFormat = MusicSourceAudioFormatPolicy.fromSourceMetadata(null, null)

    assertNull(sourceFormat)
    assertEquals("LOSSLESS", MusicSourceAudioFormatPolicy.headerLabel(".flac", sourceFormat))
  }

  @Test
  fun lossyCodecsDoNotReceiveLosslessBadge() {
    val sourceFormat = MusicSourceAudioFormatPolicy.fromSourceMetadata("24", "96 kHz")

    assertFalse(MusicSourceAudioFormatPolicy.isLosslessCodec("MP3"))
    assertNull(MusicSourceAudioFormatPolicy.headerLabel("audio/mpeg", sourceFormat))
    assertTrue(MusicSourceAudioFormatPolicy.isLosslessCodec("audio/flac"))
  }
}
