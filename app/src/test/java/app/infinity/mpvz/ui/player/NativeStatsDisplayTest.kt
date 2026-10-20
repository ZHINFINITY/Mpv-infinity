package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeStatsDisplayTest {
  @Test
  fun sourceAndRendererResolutionAreNeverSubstitutedForOneAnother() {
    assertEquals("1920×1080", NativeStatsDisplay.resolution(1920, 1080))
    assertEquals("--", NativeStatsDisplay.resolution(0, 1080))
    assertEquals("--", NativeStatsDisplay.resolution(0, 0))
  }

  @Test
  fun aspectRatioUsesSuppliedPixelRatioAndMarksMissingValues() {
    assertEquals("1.778:1", NativeStatsDisplay.aspectRatio(1920, 1080, 1f))
    assertEquals("1.778:1", NativeStatsDisplay.aspectRatio(1440, 1080, 1.3333334f))
    assertEquals("--", NativeStatsDisplay.aspectRatio(1920, 1080, null))
    assertEquals("--", NativeStatsDisplay.aspectRatio(1920, 1080, Float.NaN))
    assertEquals("--", NativeStatsDisplay.aspectRatio(0, 1080, 1f))
  }

  @Test
  fun colorDepthHdrAndFrameCountersPreserveUnknownInsteadOfInventingZero() {
    assertEquals("10-bit", NativeStatsDisplay.bitDepth(10, 10))
    assertEquals("luma 10-bit / chroma 8-bit", NativeStatsDisplay.bitDepth(10, 8))
    assertEquals("10-bit luma", NativeStatsDisplay.bitDepth(10, null))
    assertEquals("--", NativeStatsDisplay.bitDepth(null, null))
    assertEquals("16", NativeStatsDisplay.isoCode(16))
    assertEquals("--", NativeStatsDisplay.isoCode(null))
    assertEquals("present", NativeStatsDisplay.hdrStaticMetadata(true))
    assertEquals("not signalled", NativeStatsDisplay.hdrStaticMetadata(false))
    assertEquals("--", NativeStatsDisplay.hdrStaticMetadata(null))
    assertEquals("0", NativeStatsDisplay.frameCount(0L))
    assertEquals("27", NativeStatsDisplay.frameCount(27L))
    assertEquals("--", NativeStatsDisplay.frameCount(null))
  }

  @Test
  fun decoderAndAudioValuesUseUnavailableInsteadOfMisleadingDefaults() {
    assertEquals("--", NativeStatsDisplay.knownLabel(null))
    assertEquals("--", NativeStatsDisplay.knownLabel("  "))
    assertEquals("c2.android.avc.decoder", NativeStatsDisplay.knownLabel(" c2.android.avc.decoder "))
    assertEquals("--", NativeStatsDisplay.audioChannels(0))
    assertEquals("6 ch", NativeStatsDisplay.audioChannels(6))
    assertEquals("--", NativeStatsDisplay.audioSampleRate(0))
    assertEquals("48000 Hz", NativeStatsDisplay.audioSampleRate(48_000))
  }

  @Test
  fun timingAndBitrateFormattingMarksUnavailableValuesAndKeepsNegativeOffsets() {
    assertEquals("--", NativeStatsDisplay.frameRate(Float.NaN))
    assertEquals("23.98 fps", NativeStatsDisplay.frameRate(23.976f))
    assertEquals("--", NativeStatsDisplay.bitrate(0L))
    assertEquals("1.5 kbps", NativeStatsDisplay.bitrate(1_500L))
    assertEquals("--", NativeStatsDisplay.frameOffset(-250L, 0L))
    assertEquals("-0.3 ms", NativeStatsDisplay.frameOffset(-250L, 1L))
    assertEquals("--", NativeStatsDisplay.duration(0L))
    assertEquals("1:02", NativeStatsDisplay.duration(62_900L))
    assertEquals("1:01:01", NativeStatsDisplay.position(3_661_000L))
  }

  @Test
  fun bandwidthBufferAndByteSizeMappingsUseMeasuredValuesOnly() {
    assertEquals("--", NativeStatsDisplay.bandwidth(0L))
    assertEquals("2.0 kbps", NativeStatsDisplay.bandwidth(2_000L))
    assertEquals("0.0 s", NativeStatsDisplay.bufferedDuration(0L))
    assertEquals("1.5 s", NativeStatsDisplay.bufferedDuration(1_500L))
    assertEquals("--", NativeStatsDisplay.byteSize(0L))
    assertEquals("512 B", NativeStatsDisplay.byteSize(512L))
    assertEquals("1.0 KiB", NativeStatsDisplay.byteSize(1_024L))
  }
}
