package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AnvilMotionVectorSupportTest {
  @Test
  fun h264AndMpegFamilyHaveKnownFfmpegMotionVectorExporters() {
    assertEquals(
      AnvilMotionVectorExportCapability.SUPPORTED,
      anvilMotionVectorExportCapability("h264"),
    )
    assertEquals(
      AnvilMotionVectorExportCapability.SUPPORTED,
      anvilMotionVectorExportCapability("mpeg2video"),
    )
  }

  @Test
  fun hevcAliasesUseTheBundledDecoderSideDataAdapter() {
    for (codec in listOf("hevc", "h265", "hvc1", "hev1")) {
      assertEquals(
        codec,
        AnvilMotionVectorExportCapability.SUPPORTED,
        anvilMotionVectorExportCapability(codec),
      )
    }
  }

  @Test
  fun codecsWithoutAnExporterAreExplicitlyUnsupported() {
    for (codec in listOf("av1", "av01", "vp8", "vp9")) {
      assertEquals(
        codec,
        AnvilMotionVectorExportCapability.UNSUPPORTED,
        anvilMotionVectorExportCapability(codec),
      )
    }
  }

  @Test
  fun unclassifiedCodecIsNotClaimedAsSupported() {
    assertEquals(
      AnvilMotionVectorExportCapability.UNKNOWN,
      anvilMotionVectorExportCapability("some-new-codec"),
    )
    assertEquals(
      AnvilMotionVectorExportCapability.UNKNOWN,
      anvilMotionVectorExportCapability(null),
    )
  }
}
