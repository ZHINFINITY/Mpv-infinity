package app.infinity.mpvz.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** Eligibility and color-normalization policy for the Media3 Flow SDR interpolation path. */
internal object Media3FlowFormatPolicy {
  /**
   * Whether a format that reaches the Flow path would need decoder normalization to the BT.709
   * SDR video transfer curve before storage in RGBA8. HDR/Dolby Vision and ambiguous high-bit-depth
   * formats bypass Flow while interpolation is off; when on, they may use verified SDR conversion.
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
   * True when the source must retain the platform decoder/display color path while interpolation
   * is off, instead of being normalized and copied through Flow's SDR RGBA8 capture surface.
   */
  fun requiresNativeHdrPassthrough(
    mimeType: String,
    codecs: String,
    colorSpace: Int,
    colorTransfer: Int,
    lumaBitdepth: Int,
    chromaBitdepth: Int,
    hasHdrStaticInfo: Boolean,
  ): Boolean {
    if (isDolbyVision(mimeType, codecs) || hasHdrStaticInfo) return true
    if (colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG) return true

    // Missing/unspecified transfer metadata cannot safely distinguish HDR from SDR on a
    // high-bit-depth or BT.2020 stream, so leave the original metadata with Media3's normal path.
    val transferUnknown = colorTransfer == Format.NO_VALUE || colorTransfer == 0
    return transferUnknown &&
      (colorSpace == C.COLOR_SPACE_BT2020 ||
        lumaBitdepth > 8 || chromaBitdepth > 8 || codecSignalsHighBitDepth(codecs))
  }

  fun shouldReevaluateNativeRenderer(
    previousInterpolationEnabled: Boolean,
    interpolationEnabled: Boolean,
    requiresNativeHdrPassthrough: Boolean,
  ): Boolean = previousInterpolationEnabled != interpolationEnabled && requiresNativeHdrPassthrough

  /**
   * When interpolation is off, HDR/Dolby Vision and ambiguous high-bit-depth formats retain the
   * platform's native output path. When on, those formats may use verified decoder-side SDR
   * conversion for Flow. DRM and non-video routing remain caller gates.
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
    interpolationEnabled: Boolean = true,
  ): Boolean {
    if (
      !interpolationEnabled &&
      requiresNativeHdrPassthrough(
        mimeType = mimeType,
        codecs = codecs,
        colorSpace = colorSpace,
        colorTransfer = colorTransfer,
        lumaBitdepth = lumaBitdepth,
        chromaBitdepth = chromaBitdepth,
        hasHdrStaticInfo = hasHdrStaticInfo,
      )
    ) return false

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
      codec.startsWith("dvhe") ||
      codec.startsWith("dvh1") ||
      codec.startsWith("dva1") ||
      codec.startsWith("dvav") ||
      codec.startsWith("dav1")
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
