package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeStatsDisplayTest {
  @Test
  fun resolutionPrefersMeasuredOutputThenInputAndDoesNotInventDimensions() {
    assertEquals("1920×1080", NativeStatsDisplay.resolution(1920, 1080, 1280, 720))
    assertEquals("1280×720", NativeStatsDisplay.resolution(0, 0, 1280, 720))
    assertEquals("--", NativeStatsDisplay.resolution(0, 1080, 1280, 0))
  }

  @Test
  fun outputSummaryIsCompactInMoreAndExplicitAboutMissingFieldsInStatistics() {
    assertEquals(
      "1920×1080 · HDR10 · BT.2020 · HEVC",
      NativeStatsDisplay.outputSummary("1920×1080", "HDR10", "BT.2020", "HEVC"),
    )
    assertEquals(
      "1920×1080 · HDR10",
      NativeStatsDisplay.outputSummary("1920×1080", "HDR10", null, "--"),
    )
    assertEquals(
      "1920×1080 · HDR10 · -- · --",
      NativeStatsDisplay.outputSummary("1920×1080", "HDR10", null, "--", includeMissingParts = true),
    )
    assertEquals("--", NativeStatsDisplay.outputSummary("--", null, null, "--"))
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
