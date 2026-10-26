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
      listOf(false, true).forEach { interpolationEnabled ->
        assertTrue(
          "$mimeType SDR should be eligible when interpolation=$interpolationEnabled",
          supports(
            mimeType = mimeType,
            colorSpace = C.COLOR_SPACE_BT709,
            colorRange = C.COLOR_RANGE_LIMITED,
            colorTransfer = C.COLOR_TRANSFER_SDR,
            lumaBitdepth = 8,
            chromaBitdepth = 8,
            interpolationEnabled = interpolationEnabled,
          ),
        )
      }
    }
  }

  @Test
  fun untaggedTenBitHevcUsesNativeOutputOnlyWhenInterpolationIsOff() {
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
      "Ambiguous 10-bit input can use Flow when interpolation is on and SDR conversion is available",
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = codecs,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        decoderSdrRequestAvailable = true,
        interpolationEnabled = true,
      ),
    )
    assertFalse(
      "Ambiguous 10-bit input must preserve native output when interpolation is off",
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = codecs,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        decoderSdrRequestAvailable = true,
        interpolationEnabled = false,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = codecs,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        decoderSdrRequestAvailable = false,
        interpolationEnabled = true,
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
  fun hdrAndDolbyVisionUseNativePassthroughOnlyWhenInterpolationIsOff() {
    val hdrCases = listOf(
      Triple(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0", C.COLOR_TRANSFER_ST2084),
      Triple(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0", C.COLOR_TRANSFER_HLG),
      Triple(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.05.06", C.COLOR_TRANSFER_ST2084),
    )
    hdrCases.forEach { (mimeType, codecs, transfer) ->
      assertTrue(
        "$mimeType/$codecs would require SDR normalization if copied into Flow RGBA8",
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
        "$mimeType/$codecs may use Flow when interpolation is on and decoder conversion is available",
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = C.COLOR_SPACE_BT2020,
          colorRange = C.COLOR_RANGE_LIMITED,
          colorTransfer = transfer,
          lumaBitdepth = 10,
          chromaBitdepth = 10,
          decoderSdrRequestAvailable = true,
          interpolationEnabled = true,
        ),
      )
      assertFalse(
        "$mimeType/$codecs must remain native when interpolation is off",
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = C.COLOR_SPACE_BT2020,
          colorRange = C.COLOR_RANGE_LIMITED,
          colorTransfer = transfer,
          lumaBitdepth = 10,
          chromaBitdepth = 10,
          decoderSdrRequestAvailable = true,
          interpolationEnabled = false,
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
          interpolationEnabled = true,
        ),
      )
    }

    assertTrue(
      "HDR static metadata may use Flow with interpolation enabled and decoder conversion available",
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorTransfer = C.COLOR_TRANSFER_SDR,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        hasHdrStaticInfo = true,
        decoderSdrRequestAvailable = true,
        interpolationEnabled = true,
      ),
    )
    assertFalse(
      "HDR static metadata must retain native output when interpolation is off",
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorTransfer = C.COLOR_TRANSFER_SDR,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        hasHdrStaticInfo = true,
        decoderSdrRequestAvailable = true,
        interpolationEnabled = false,
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
        interpolationEnabled = true,
      ),
    )
  }

  @Test
  fun untaggedHighBitDepthAndWideGamutUseNativeOutputOnlyWhenInterpolationIsOff() {
    val cases = listOf(
      Triple(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0", C.COLOR_SPACE_BT2020),
      Triple(MimeTypes.VIDEO_VP9, "vp09.02.10.10.01.09.16.09.01", Format.NO_VALUE),
      Triple(MimeTypes.VIDEO_AV1, "av01.0.08M.10.0.110.09.16.09.0", Format.NO_VALUE),
      Triple(MimeTypes.VIDEO_H264, "avc1.6E0034", Format.NO_VALUE),
    )
    cases.forEach { (mimeType, codecs, colorSpace) ->
      assertTrue(
        "$mimeType/$codecs would require SDR normalization if copied into Flow RGBA8",
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
        "$mimeType/$codecs may use Flow when interpolation is on and SDR conversion is available",
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = colorSpace,
          decoderSdrRequestAvailable = true,
          interpolationEnabled = true,
        ),
      )
      assertFalse(
        "$mimeType/$codecs must keep native output when interpolation is off",
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = colorSpace,
          decoderSdrRequestAvailable = true,
          interpolationEnabled = false,
        ),
      )
      assertFalse(
        supports(
          mimeType = mimeType,
          codecs = codecs,
          colorSpace = colorSpace,
          decoderSdrRequestAvailable = false,
          interpolationEnabled = true,
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
      "Explicitly tagged BT.2020 SDR remains eligible for Flow",
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = "hvc1.2.4.L120.B0",
        colorSpace = C.COLOR_SPACE_BT2020,
        colorRange = C.COLOR_RANGE_LIMITED,
        colorTransfer = C.COLOR_TRANSFER_SDR,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
      ),
    )
    assertTrue(
      "Explicitly tagged BT.2020 SDR stays eligible when interpolation is off",
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        codecs = "hvc1.2.4.L120.B0",
        colorSpace = C.COLOR_SPACE_BT2020,
        colorRange = C.COLOR_RANGE_LIMITED,
        colorTransfer = C.COLOR_TRANSFER_SDR,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        interpolationEnabled = false,
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
  fun actualFormatGateAcceptsExplicitSdrTenBitRotationAndAnamorphicPixels() {
    val colorInfo =
      ColorInfo.Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(C.COLOR_TRANSFER_SDR)
        .setLumaBitdepth(10)
        .setChromaBitdepth(10)
        .setHdrStaticInfo(byteArrayOf())
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
    assertTrue(Media3FlowVideoSink.supportsFormat(format, sdkInt = 34, interpolationEnabled = false))
    assertTrue(Media3FlowVideoSink.supportsFormat(format, sdkInt = 30))
  }

  @Test
  fun actualHdrFormatUsesNativeOutputOnlyWhenInterpolationIsOff() {
    val hdrColorInfo =
      ColorInfo.Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
        .setLumaBitdepth(10)
        .setChromaBitdepth(10)
        .build()
    val format =
      Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265)
        .setWidth(1920)
        .setHeight(1080)
        .setColorInfo(hdrColorInfo)
        .build()

    assertTrue(Media3FlowVideoSink.supportsFormat(format, sdkInt = 34, interpolationEnabled = true))
    assertFalse(Media3FlowVideoSink.supportsFormat(format, sdkInt = 34, interpolationEnabled = false))
    assertTrue(Media3FlowVideoSink.requiresNativeHdrPassthrough(format))
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

  @Test
  fun dolbyVisionCaptureSourceIsRecognizedEvenWhenColorTransferLooksSdr() {
    val dolbyVisionCodecs =
      listOf(
        MimeTypes.VIDEO_H265 to "dvhe.05.06",
        MimeTypes.VIDEO_H265 to "dvh1.08.06",
        MimeTypes.VIDEO_H264 to "dva1.05.06",
        MimeTypes.VIDEO_AV1 to "dvav.09.06",
        MimeTypes.VIDEO_AV1 to "dav1.09.06",
      )
    dolbyVisionCodecs.forEach { (mimeType, codec) ->
      assertTrue(codec, Media3FlowFormatPolicy.isDolbyVision(mimeType, codec))
      assertFalse(
        "$codec must remain native when interpolation is off even if transfer metadata says SDR",
        supports(
          mimeType = mimeType,
          codecs = codec,
          colorTransfer = C.COLOR_TRANSFER_SDR,
          lumaBitdepth = 8,
          chromaBitdepth = 8,
          interpolationEnabled = false,
        ),
      )
      assertTrue(
        "$codec may use Flow when interpolation is on and decoder-side SDR conversion is available",
        supports(
          mimeType = mimeType,
          codecs = codec,
          colorTransfer = C.COLOR_TRANSFER_SDR,
          lumaBitdepth = 8,
          chromaBitdepth = 8,
          interpolationEnabled = true,
        ),
      )
    }
    assertTrue(Media3FlowFormatPolicy.isDolbyVision(MimeTypes.VIDEO_DOLBY_VISION, ""))
    assertFalse(Media3FlowFormatPolicy.isDolbyVision(MimeTypes.VIDEO_H265, "hvc1.1.6.L93.B0"))
  }

  @Test
  fun rendererSelectionIsReevaluatedOnlyWhenNativePassthroughEligibilityChanges() {
    assertTrue(Media3FlowFormatPolicy.shouldReevaluateNativeRenderer(true, false, true))
    assertTrue(Media3FlowFormatPolicy.shouldReevaluateNativeRenderer(false, true, true))
    assertFalse(Media3FlowFormatPolicy.shouldReevaluateNativeRenderer(true, true, true))
    assertFalse(Media3FlowFormatPolicy.shouldReevaluateNativeRenderer(true, false, false))
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
    interpolationEnabled: Boolean = true,
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
      interpolationEnabled = interpolationEnabled,
    )
  }
}
