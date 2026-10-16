package app.infinity.mpvz.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** Eligibility and color-normalization policy for the Media3 Flow SDR interpolation path. */
internal object Media3FlowFormatPolicy {
  /**
   * Whether the decoder must normalize its output to the BT.709 SDR video transfer curve before
   * Media3Flow stores pixels in RGBA8. The request is made only through the decoder's documented
   * color-transfer request; a later output-format check rejects a request the codec ignored.
   */
  fun requiresSdrOutputRequest(
    mimeType: String,
    codecs: String,
    colorSpace: Int,
    colorRange: Int,
    colorTransfer: Int,
    lumaBitdepth: Int,
    chromaBitdepth: Int,
    hasHdrStaticInfo: Boolean,
  ): Boolean {
    if (isDolbyVision(mimeType, codecs) || hasHdrStaticInfo) return true
    if (colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG) return true
    if (colorTransfer == C.COLOR_TRANSFER_SDR) return false

    // Missing transfer/VUI metadata on high-bit-depth content is ambiguous; normalize it before
    // copying into RGBA8 rather than assuming that Main10 means SDR.
    if (colorTransfer != Format.NO_VALUE && colorTransfer != C.COLOR_TRANSFER_SDR) return true
    if (colorRange == C.COLOR_RANGE_FULL) return true
    if (colorSpace == C.COLOR_SPACE_BT2020) return true
    if (lumaBitdepth > 8 || chromaBitdepth > 8 || codecSignalsHighBitDepth(codecs)) return true
    return false
  }

  /**
   * The Flow renderer may be selected only when the source is already SDR/default SDR, or the
   * platform supports the decoder-side SDR request. DRM and non-video routing remain caller gates.
   */
  fun supportsSdrInterpolation(
    mimeType: String,
    codecs: String,
    colorSpace: Int,
    colorRange: Int,
    colorTransfer: Int,
    lumaBitdepth: Int,
    chromaBitdepth: Int,
    hasHdrStaticInfo: Boolean,
    isEquivalentToAssumedSdrDefault: Boolean,
    decoderSdrRequestAvailable: Boolean,
  ): Boolean {
    val requiresRequest = requiresSdrOutputRequest(
      mimeType = mimeType,
      codecs = codecs,
      colorSpace = colorSpace,
      colorRange = colorRange,
      colorTransfer = colorTransfer,
      lumaBitdepth = lumaBitdepth,
      chromaBitdepth = chromaBitdepth,
      hasHdrStaticInfo = hasHdrStaticInfo,
    )
    if (requiresRequest) return decoderSdrRequestAvailable

    return colorTransfer == C.COLOR_TRANSFER_SDR || isEquivalentToAssumedSdrDefault
  }

  /** True when the decoder output's declared primaries need conversion to the SDR BT.709 target. */
  fun shouldConvertBt2020ToBt709(
    inputColorSpace: Int,
    outputColorStandard: Int,
    mimeType: String,
    codecs: String,
  ): Boolean {
    if (outputColorStandard != Format.NO_VALUE) {
      return outputColorStandard == C.COLOR_SPACE_BT2020
    }
    return inputColorSpace == C.COLOR_SPACE_BT2020 || isDolbyVision(mimeType, codecs)
  }

  fun isDolbyVision(mimeType: String, codecs: String): Boolean {
    val mime = mimeType.lowercase()
    val codec = codecs.lowercase().substringBefore(',').trim()
    return mime == MimeTypes.VIDEO_DOLBY_VISION ||
      codec.startsWith("dvhe") || codec.startsWith("dvh1")
  }

  private fun codecSignalsHighBitDepth(codecs: String): Boolean {
    val parts = codecs.lowercase().substringBefore(',').trim().split('.')
    return when (parts.firstOrNull()) {
      "dvhe", "dvh1" -> true
      "hvc1", "hev1" -> parts.getOrNull(1)?.toIntOrNull()?.let { it >= 2 } == true
      "vp09" -> parts.getOrNull(1)?.toIntOrNull()?.let { it >= 2 } == true
      "av01" -> parts.getOrNull(3)?.toIntOrNull()?.let { it > 8 } == true
      "avc1", "avc3" -> {
        val profile = parts.getOrNull(1)?.take(2)?.toIntOrNull(16)
        profile == 110 || profile == 122 || profile == 244
      }
      else -> false
    }
  }
}
