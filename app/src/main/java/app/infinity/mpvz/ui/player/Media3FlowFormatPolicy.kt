package app.infinity.mpvz.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/** Eligibility and color-normalization policy for the Media3 Flow SDR video path. */
internal object Media3FlowFormatPolicy {
  /**
   * Whether a format that reaches the Flow path would need decoder normalization to the BT.709
   * SDR video transfer curve before storage in RGBA8. Explicit HDR/Dolby Vision and ambiguous
   * high-bit-depth formats bypass Flow while interpolation is off, except for the legacy SDR
   * Main10 HEVC compatibility case handled by [isLegacySdrMain10Hevc].
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
    colorRange: Int,
    colorTransfer: Int,
    lumaBitdepth: Int,
    chromaBitdepth: Int,
    hasHdrStaticInfo: Boolean,
    width: Int,
    height: Int,
  ): Boolean {
    if (isDolbyVision(mimeType, codecs) || hasHdrStaticInfo) return true
    if (colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG) return true

    // Missing/unspecified transfer metadata cannot safely distinguish HDR from SDR on a
    // high-bit-depth or BT.2020 stream. The old Native Media3 path did play untagged Main10 HEVC
    // at up to 1080p as SDR; keep that narrow behavior when no explicit HDR/gamut signal exists.
    val transferUnknown = colorTransfer == Format.NO_VALUE || colorTransfer == 0
    if (
      transferUnknown &&
      isLegacySdrMain10Hevc(
        mimeType = mimeType,
        codecs = codecs,
        colorSpace = colorSpace,
        colorRange = colorRange,
        hasHdrStaticInfo = hasHdrStaticInfo,
        width = width,
        height = height,
      )
    ) return false

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
   * platform's native output path, except for the narrowly scoped legacy SDR Main10 HEVC case.
   * When on, those formats may use verified decoder-side SDR conversion for Flow. DRM and
   * non-video routing remain caller gates.
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
    width: Int = Format.NO_VALUE,
    height: Int = Format.NO_VALUE,
  ): Boolean {
    if (
      !interpolationEnabled &&
      requiresNativeHdrPassthrough(
        mimeType = mimeType,
        codecs = codecs,
        colorSpace = colorSpace,
        colorRange = colorRange,
        colorTransfer = colorTransfer,
        lumaBitdepth = lumaBitdepth,
        chromaBitdepth = chromaBitdepth,
        hasHdrStaticInfo = hasHdrStaticInfo,
        width = width,
        height = height,
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

  private fun isLegacySdrMain10Hevc(
    mimeType: String,
    codecs: String,
    colorSpace: Int,
    colorRange: Int,
    hasHdrStaticInfo: Boolean,
    width: Int,
    height: Int,
  ): Boolean {
    if (mimeType != MimeTypes.VIDEO_H265 || hasHdrStaticInfo) return false
    val colorSpaceUnknown = colorSpace == Format.NO_VALUE || colorSpace == 0
    val colorRangeUnspecifiedOrLimited =
      colorRange == Format.NO_VALUE || colorRange == 0 || colorRange == C.COLOR_RANGE_LIMITED
    val dimensionsAre1080pOrSmaller =
      width > 0 && height > 0 && maxOf(width, height) <= 1920 && minOf(width, height) <= 1080
    val codecParts = codecs.lowercase().substringBefore(',').trim().split('.')
    val codecName = codecParts.firstOrNull()
    val isMain10Profile =
      (codecName == "hvc1" || codecName == "hev1") &&
        codecParts.getOrNull(1)?.toIntOrNull() == 2
    return colorSpaceUnknown && colorRangeUnspecifiedOrLimited &&
      dimensionsAre1080pOrSmaller && isMain10Profile
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
