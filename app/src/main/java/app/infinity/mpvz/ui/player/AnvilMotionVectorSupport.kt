/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

/**
 * Whether the bundled FFmpeg 9.0.2 source has a decoder path that serializes codec vectors as
 * AV_FRAME_DATA_MOTION_VECTORS. This predicts decoder capability, not whether a particular frame
 * contains vectors; ANVIL's per-frame side-data telemetry remains authoritative.
 */
enum class AnvilMotionVectorExportCapability {
  SUPPORTED,
  UNSUPPORTED,
  UNKNOWN,
}

fun anvilMotionVectorExportCapability(codecName: String?): AnvilMotionVectorExportCapability {
  val codec = codecName?.trim()?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()
  if (codec.isBlank()) return AnvilMotionVectorExportCapability.UNKNOWN

  return when (codec) {
    "h264", "avc", "avc1",
    "h261", "h263", "h263i", "h263p",
    "mpeg1video", "mpeg2video", "mpeg4",
    "msmpeg4v1", "msmpeg4v2", "msmpeg4v3",
    "wmv1", "wmv2", "wmv3", "flv1",
    "rv10", "rv20", "rv30", "rv40", "vc1", "snow",
    "hevc", "h265", "hvc1", "hev1" -> AnvilMotionVectorExportCapability.SUPPORTED

    "av1", "av01", "vp8", "vp9" ->
      AnvilMotionVectorExportCapability.UNSUPPORTED

    else -> AnvilMotionVectorExportCapability.UNKNOWN
  }
}
