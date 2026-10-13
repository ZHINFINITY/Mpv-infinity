package app.infinity.mpvz.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** Format gate for the SDR-only Media3 Flow interpolation path. */
internal object Media3FlowFormatPolicy {
  /**
   * Accepts explicitly SDR formats as before. The only unknown-transfer exception is untagged
   * Main10 HEVC with no HDR static metadata, no BT.2020 primaries, and a default/limited range.
   * The affected POCO F7 HEVC decoder resolves this exact missing-VUI case to BT.709 limited SDR;
   * explicit HDR transfer characteristics and HDR color hints remain unsupported.
   */
  fun supportsSdrInterpolation(
    mimeType: String,
    colorSpace: Int,
    colorRange: Int,
    colorTransfer: Int,
    lumaBitdepth: Int,
    chromaBitdepth: Int,
    hasHdrStaticInfo: Boolean,
  ): Boolean {
    if (colorTransfer == C.COLOR_TRANSFER_SDR) return true
    if (mimeType != MimeTypes.VIDEO_H265 || colorTransfer != Format.NO_VALUE) return false

    val defaultSdrColorSpace =
      colorSpace == Format.NO_VALUE ||
        colorSpace == C.COLOR_SPACE_BT709 ||
        colorSpace == C.COLOR_SPACE_BT601
    val defaultSdrColorRange =
      colorRange == Format.NO_VALUE || colorRange == C.COLOR_RANGE_LIMITED

    return lumaBitdepth == 10 &&
      chromaBitdepth == 10 &&
      defaultSdrColorSpace &&
      defaultSdrColorRange &&
      !hasHdrStaticInfo
  }
}
