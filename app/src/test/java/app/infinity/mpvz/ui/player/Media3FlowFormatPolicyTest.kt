package app.infinity.mpvz.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowFormatPolicyTest {
  @Test
  fun explicitlySdrFormatsRemainEligible() {
    assertTrue(
      supports(
        mimeType = MimeTypes.VIDEO_H264,
        colorSpace = C.COLOR_SPACE_BT709,
        colorRange = C.COLOR_RANGE_LIMITED,
        colorTransfer = C.COLOR_TRANSFER_SDR,
        lumaBitdepth = 8,
        chromaBitdepth = 8,
      ),
    )
  }

  @Test
  fun untaggedTenBitHevcWithDefaultSdrHintsIsEligible() {
    assertTrue(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorSpace = Format.NO_VALUE,
        colorRange = Format.NO_VALUE,
        colorTransfer = Format.NO_VALUE,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
      ),
    )
  }

  @Test
  fun explicitHdrTransfersAndBt2020UnknownTransferRemainIneligible() {
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorSpace = C.COLOR_SPACE_BT2020,
        colorRange = C.COLOR_RANGE_LIMITED,
        colorTransfer = C.COLOR_TRANSFER_ST2084,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorSpace = C.COLOR_SPACE_BT709,
        colorRange = C.COLOR_RANGE_LIMITED,
        colorTransfer = C.COLOR_TRANSFER_HLG,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorSpace = C.COLOR_SPACE_BT2020,
        colorRange = Format.NO_VALUE,
        colorTransfer = Format.NO_VALUE,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
      ),
    )
  }

  @Test
  fun unknownTransferExceptionDoesNotCoverOtherDepthsRangesOrCodecs() {
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        lumaBitdepth = 8,
        chromaBitdepth = 8,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        lumaBitdepth = 12,
        chromaBitdepth = 12,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H264,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        colorRange = C.COLOR_RANGE_FULL,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
      ),
    )
    assertFalse(
      supports(
        mimeType = MimeTypes.VIDEO_H265,
        lumaBitdepth = 10,
        chromaBitdepth = 10,
        hasHdrStaticInfo = true,
      ),
    )
  }

  private fun supports(
    mimeType: String,
    colorSpace: Int = Format.NO_VALUE,
    colorRange: Int = Format.NO_VALUE,
    colorTransfer: Int = Format.NO_VALUE,
    lumaBitdepth: Int,
    chromaBitdepth: Int,
    hasHdrStaticInfo: Boolean = false,
  ): Boolean =
    Media3FlowFormatPolicy.supportsSdrInterpolation(
      mimeType = mimeType,
      colorSpace = colorSpace,
      colorRange = colorRange,
      colorTransfer = colorTransfer,
      lumaBitdepth = lumaBitdepth,
      chromaBitdepth = chromaBitdepth,
      hasHdrStaticInfo = hasHdrStaticInfo,
    )
}
