package app.infinity.mpvz.ui.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowFormatPolicyTest {
  @Test
  fun explicitSdrFormatsAcrossCommonCodecsRemainEligible() {
    listOf(
      MimeTypes.VIDEO_H264,
      MimeTypes.VIDEO_H265,
      MimeTypes.VIDEO_VP9,
      MimeTypes.VIDEO_AV1,
    ).forEach { mimeType ->
      assertTrue(
        "$mimeType SDR should be eligible",
        supports(
          mimeType = mimeType,
          colorSpace = C.COLOR_SPACE_BT709,
          colorRange = C.COLOR_RANGE_LIMITED,
          colorTransfer = C.COLOR_TRANSFER_SDR,
          lumaBitdepth = 8,
          chromaBitdepth = 8,
        ),
      )
    }
  }

  @Test
  fun untaggedTenBitHevcRequiresVerifiedDecoderSdrNormalization() {
    val codecs = "hvc1.2.4.H120.90"
    assertTrue(
      Media3FlowFormatPolicy.requiresSdrOutputRequest(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = codecs,
        colorSpace = Format.NO_VALUE,
        colorRange = Format.NO_VALUE,
        colorTransfer = Format.NO_VALUE,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        hasHdrStaticInfo = false,
      ),
    )
    assertTrue(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = codecs,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        decoderSdrRequestAvailable = true,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = codecs,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        decoderSdrRequestAvailable = false,
      ),
    )
  }

  @Test
  fun untaggedHigherDepthHevcProfilesAlsoRequireSdrNormalization() {
    listOf("hvc1.2.4.L120.B0", "hvc1.4.10.L120.B0").forEach { codecs ->
      assertTrue(
        codecs,
        Media3FlowFormatPolicy.requiresSdrOutputRequest(
          mimeType = MimeTypes.VIDEO_H265,
          codecs = codecs,
          colorSpace = Format.NO_VALUE,
          colorRange = Format.NO_VALUE,
          colorTransfer = Format.NO_VALUE,
          lumaBitdepth = Format.NO_VALUE,
          chromaBitdepth = Format.NO_VALUE,
          hasHdrStaticInfo = false,
        ),
      )
    }
  }

  @Test
  fun defaultOrMissingEightBitColorMetadataUsesTheStandardSdrAssumption() {
    assertTrue(
      supports(
        mimeType = MimeTypes.VIDEO_H264,
        colorSpace = Format.NO_VALUE,
        colorRange = Format.NO_VALUE,
        colorTransfer = Format.NO_VALUE,
        lumaBitdepth = Format.NO_VALUE,
        chromaBitdepth = Format.NO_VALUE,
      ),
    )
  }

  @Test
  fun unknownTransferAndFullRangeMetadataRequestSdrNormalization() {
    assertTrue(
      supports(
        mimeType = MimeTypes.VIDEO_H264,
        colorTransfer = 0,
        lumaBitdepth = 8,
        chromaBitdepth = 8,
        decoderSdrRequestAvailable = true,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H264,
        colorTransfer = 0,
        lumaBitdepth = 8,
        chromaBitdepth = 8,
        decoderSdrRequestAvailable = false,
      ),
    )
    assertTrue(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorSpace = C.COLOR_SPACE_BT709,
        colorRange = C.COLOR_RANGE_FULL,
        colorTransfer = Format.NO_VALUE,
        lumaBitdepth = 8,
        chromaBitdepth = 8,
        decoderSdrRequestAvailable = true,
      ),
    )
  }

  @Test
  fun hdrTransfersStaticMetadataAndDolbyVisionRequireDecoderSdrOutput() {
    val hdrCases = listOf(
      Triple(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0", C.COLOR_TRANSFER_ST2084),
      Triple(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0", C.COLOR_TRANSFER_HLG),
      Triple(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.05.06", C.COLOR_TRANSFER_ST2084),
    )
    hdrCases.forEach { (mimeType, codecs, transfer) ->
      assertTrue(
        "$mimeType/$codecs should request SDR output",
        Media3FlowFormatPolicy.requiresSdrOutputRequest(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = C.COLOR_SPACE_BT2020,
          colorRange = C.COLOR_RANGE_LIMITED,
          colorTransfer = transfer,
          lumaBitdepth = 10,
          chromaBitdepth = 10,
          hasHdrStaticInfo = false,
        ),
      )
      assertTrue(
        "$mimeType/$codecs should be eligible when decoder-side SDR conversion is available",
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = C.COLOR_SPACE_BT2020,
          colorRange = C.COLOR_RANGE_LIMITED,
          colorTransfer = transfer,
          lumaBitdepth = 10,
          chromaBitdepth = 10,
          decoderSdrRequestAvailable = true,
        ),
      )
      assertFalse(
        "$mimeType/$codecs must stay on the stock path when decoder-side SDR conversion is unavailable",
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = C.COLOR_SPACE_BT2020,
          colorRange = C.COLOR_RANGE_LIMITED,
          colorTransfer = transfer,
          lumaBitdepth = 10,
          chromaBitdepth = 10,
          decoderSdrRequestAvailable = false,
        ),
      )
    }

    assertTrue(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorTransfer = C.COLOR_TRANSFER_SDR,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        hasHdrStaticInfo = true,
        decoderSdrRequestAvailable = true,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorTransfer = C.COLOR_TRANSFER_SDR,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        hasHdrStaticInfo = true,
        decoderSdrRequestAvailable = false,
      ),
    )
  }

  @Test
  fun untaggedHighBitDepthWideGamutAndUnusualTransfersRequestSdrOutput() {
    val cases = listOf(
      Triple(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0", C.COLOR_SPACE_BT2020),
      Triple(MimeTypes.VIDEO_VP9, "vp09.02.10.10.01.09.16.09.01", Format.NO_VALUE),
      Triple(MimeTypes.VIDEO_AV1, "av01.0.08M.10.0.110.09.16.09.0", Format.NO_VALUE),
      Triple(MimeTypes.VIDEO_H264, "avc1.6E0034", Format.NO_VALUE),
    )
    cases.forEach { (mimeType, codecs, colorSpace) ->
      assertTrue(
        "$mimeType/$codecs must request SDR normalization",
        Media3FlowFormatPolicy.requiresSdrOutputRequest(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = colorSpace,
          colorRange = Format.NO_VALUE,
          colorTransfer = Format.NO_VALUE,
          lumaBitdepth = Format.NO_VALUE,
          chromaBitdepth = Format.NO_VALUE,
          hasHdrStaticInfo = false,
        ),
      )
      assertTrue(
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = colorSpace,
          decoderSdrRequestAvailable = true,
        ),
      )
      assertFalse(
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = colorSpace,
          decoderSdrRequestAvailable = false,
        ),
      )
    }
  }

  @Test
  fun explicitBt2020SdrNeedsGamutConversionButNotToneMapping() {
    assertFalse(
      Media3FlowFormatPolicy.requiresSdrOutputRequest(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = "hvc1.2.4.L120.B0",
        colorSpace = C.COLOR_SPACE_BT2020,
        colorRange = C.COLOR_RANGE_LIMITED,
        colorTransfer = C.COLOR_TRANSFER_SDR,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        hasHdrStaticInfo = false,
      ),
    )
    assertTrue(
      Media3FlowFormatPolicy.shouldConvertBt2020ToBt709(
        inputColorSpace = C.COLOR_SPACE_BT2020,
        outputColorStandard = Format.NO_VALUE,
        mimeType = MimeTypes.VIDEO_H265,
        codecs = "hvc1.2.4.L120.B0",
      ),
    )
    assertTrue(
      Media3FlowFormatPolicy.shouldConvertBt2020ToBt709(
        inputColorSpace = Format.NO_VALUE,
        outputColorStandard = C.COLOR_SPACE_BT2020,
        mimeType = MimeTypes.VIDEO_H265,
        codecs = "hvc1.2.4.L120.B0",
      ),
    )
    assertFalse(
      Media3FlowFormatPolicy.shouldConvertBt2020ToBt709(
        inputColorSpace = C.COLOR_SPACE_BT2020,
        outputColorStandard = C.COLOR_SPACE_BT709,
        mimeType = MimeTypes.VIDEO_H265,
        codecs = "hvc1.2.4.L120.B0",
      ),
    )
  }

  @Test
  fun actualFormatGateAcceptsMain10RotationAndAnamorphicPixels() {
    val colorInfo =
      ColorInfo.Builder()
        .setColorSpace(Format.NO_VALUE)
        .setColorRange(Format.NO_VALUE)
        .setColorTransfer(Format.NO_VALUE)
        .setLumaBitdepth(10)
        .setChromaBitdepth(10)
        .build()
    val format =
      Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setWidth(16)
        .setHeight(16)
        .setRotationDegrees(90)
        .setPixelWidthHeightRatio(1.25f)
        .setColorInfo(colorInfo)
        .build()

    assertTrue(Media3FlowVideoSink.supportsFormat(format, sdkInt = 34))
    assertFalse(Media3FlowVideoSink.supportsFormat(format, sdkInt = 30))
  }

  @Test
  fun actualFormatGateRejectsInvalidRotationAndNonPositivePixelRatio() {
    val sdrColorInfo =
      ColorInfo.Builder()
        .setColorTransfer(C.COLOR_TRANSFER_SDR)
        .setLumaBitdepth(8)
        .setChromaBitdepth(8)
        .build()
    val invalidRotation =
      Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(320)
        .setHeight(240)
        .setRotationDegrees(45)
        .setColorInfo(sdrColorInfo)
        .build()
    val invalidPixelRatio =
      Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(320)
        .setHeight(240)
        .setPixelWidthHeightRatio(0f)
        .setColorInfo(sdrColorInfo)
        .build()

    assertFalse(Media3FlowVideoSink.supportsFormat(invalidRotation))
    assertFalse(Media3FlowVideoSink.supportsFormat(invalidPixelRatio))
  }

  private fun supports(
    mimeType: String,
    codecs: String = "",
    colorSpace: Int = Format.NO_VALUE,
    colorRange: Int = Format.NO_VALUE,
    colorTransfer: Int = Format.NO_VALUE,
    lumaBitdepth: Int = Format.NO_VALUE,
    chromaBitdepth: Int = Format.NO_VALUE,
    hasHdrStaticInfo: Boolean = false,
    decoderSdrRequestAvailable: Boolean = true,
  ): Boolean {
    val colorInfo =
      ColorInfo.Builder()
        .setColorSpace(colorSpace)
        .setColorRange(colorRange)
        .setColorTransfer(colorTransfer)
        .setLumaBitdepth(lumaBitdepth)
        .setChromaBitdepth(chromaBitdepth)
        .setHdrStaticInfo(if (hasHdrStaticInfo) byteArrayOf(1) else null)
        .build()
    return Media3FlowFormatPolicy.supportsSdrInterpolation(
      mimeType = mimeType,
      codecs = codecs,
      colorSpace = colorInfo.colorSpace,
      colorRange = colorInfo.colorRange,
      colorTransfer = colorInfo.colorTransfer,
      lumaBitdepth = colorInfo.lumaBitdepth,
      chromaBitdepth = colorInfo.chromaBitdepth,
      hasHdrStaticInfo = colorInfo.hdrStaticInfo != null,
      isEquivalentToAssumedSdrDefault = ColorInfo.isEquivalentToAssumedSdrDefault(colorInfo),
      decoderSdrRequestAvailable = decoderSdrRequestAvailable,
    )
  }
}
