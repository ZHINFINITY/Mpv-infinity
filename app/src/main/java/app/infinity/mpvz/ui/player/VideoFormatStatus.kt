/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.media3.common.C
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The source transfer format and the format currently reported for the output pipeline. */
data class VideoFormatStatus(
  val sourceLabel: String,
  val outputLabel: String,
)

/**
 * Classifies the format reported by Media3 when the MPV property store is not authoritative.
 */
fun detectMedia3VideoFormatStatus(
  mimeType: String?,
  codecs: String?,
  colorSpace: Int,
  colorTransfer: Int,
): VideoFormatStatus? {
  val values = listOf(mimeType, codecs).filterNotNull().joinToString(" ").lowercase()
  if (values.isBlank()) return null
  val isDolbyVision = values.contains("dolby-vision") || values.contains("dolby_vision") || values.contains("dvhe") || values.contains("dvh1")
  val isHdr10 =
    colorSpace == C.COLOR_SPACE_BT2020 && colorTransfer == C.COLOR_TRANSFER_ST2084
  val isHlg =
    colorSpace == C.COLOR_SPACE_BT2020 && colorTransfer == C.COLOR_TRANSFER_HLG
  val sourceLabel = when {
    isDolbyVision -> "Dolby Vision"
    isHdr10 -> "HDR10"
    isHlg -> "HLG"
    values.contains("10") -> "10-bit video"
    else -> "SDR"
  }
  val outputLabel = when {
    isDolbyVision || isHdr10 || isHlg -> "Media3 HDR pipeline"
    else -> "Media3 renderer"
  }
  return VideoFormatStatus(sourceLabel = sourceLabel, outputLabel = outputLabel)
}

/**
 * Classifies the source and output independently.
 A Dolby Vision source can legitimately be
 * rendered as HDR10 or tone-mapped to SDR, so the UI must never conflate the two values.
 */
fun detectVideoFormatStatus(
  videoTrack: TrackNode?,
  sourcePrimaries: String?,
  sourceGamma: String?,
  sourcePixelFormat: String?,
  sourceMaxLuma: Double?,
  sourceMaxCll: Double?,
  sourceMaxFall: Double?,
  outputPrimaries: String?,
  outputGamma: String?,
  outputPixelFormat: String?,
): VideoFormatStatus? {
  if (videoTrack == null && sourcePrimaries == null && sourceGamma == null && outputPrimaries == null && outputGamma == null) {
    return null
  }

  val sourceValues = listOf(
    videoTrack?.codec,
    videoTrack?.codecDesc,
    videoTrack?.codecProfile,
    videoTrack?.formatName,
    videoTrack?.metadata?.values?.joinToString(" "),
  ).filterNotNull().joinToString(" ").lowercase()
  val sourcePrimariesValue = sourcePrimaries.orEmpty().lowercase()
  val sourceGammaValue = sourceGamma.orEmpty().lowercase()
  val sourcePixelFormatValue = sourcePixelFormat.orEmpty().lowercase()
  val isBt2020Source = sourcePrimariesValue.contains("bt.2020") || sourcePrimariesValue.contains("2020")
  val isPqSource = sourceGammaValue.contains("pq") || sourceGammaValue.contains("2084") || sourceGammaValue.contains("smpte2084")
  val isHlgSource = sourceGammaValue.contains("hlg")
  val hasHdrStaticMetadata = sourceMaxLuma != null || sourceMaxCll != null || sourceMaxFall != null
  val isHdr10PlusSource = videoTrack?.metadata?.keys?.any { key ->
    key.contains("hdr10+", ignoreCase = true) || key.contains("2094", ignoreCase = true)
  } == true
  val isDolbyVision =
    videoTrack?.dolbyVisionProfile != null ||
      sourceValues.contains("dolby vision") ||
      Regex("\\bdv(?:he|h1)\\.?(?:[0-9]{2})?").containsMatchIn(sourceValues)

  val sourceLabel = when {
    isDolbyVision -> {
      val profile = videoTrack?.dolbyVisionProfile?.let { " P$it" }.orEmpty()
      if (videoTrack?.dolbyVisionProfile == 8L) {
        "Dolby Vision$profile (HDR10 compatible)"
      } else {
        "Dolby Vision$profile"
      }
    }
    isHdr10PlusSource && isBt2020Source -> "HDR10+"
    isBt2020Source && (isPqSource || hasHdrStaticMetadata) -> "HDR10"
    isBt2020Source && isHlgSource -> "HLG"
    isBt2020Source && sourcePixelFormatValue.contains("10") -> "BT.2020 · 10-bit"
    sourcePixelFormatValue.contains("10") -> "SDR · 10-bit"
    else -> "SDR"
  }

  val outputPrimariesValue = outputPrimaries.orEmpty().lowercase()
  val outputGammaValue = outputGamma.orEmpty().lowercase()
  val outputPixelFormatValue = outputPixelFormat.orEmpty().lowercase()
  val isBt2020Output = outputPrimariesValue.contains("bt.2020") || outputPrimariesValue.contains("2020")
  val isPqOutput = outputGammaValue.contains("pq") || outputGammaValue.contains("2084") || outputGammaValue.contains("smpte2084")
  val isHlgOutput = outputGammaValue.contains("hlg")
  val outputLabel = when {
    isBt2020Output && isPqOutput -> "HDR10"
    isBt2020Output && isHlgOutput -> "HLG"
    outputPrimariesValue.isBlank() && outputGammaValue.isBlank() -> "Unknown"
    outputPixelFormatValue.isNotBlank() && outputPrimariesValue.contains("bt.709") -> "SDR"
    outputPrimariesValue.contains("bt.709") || outputPrimariesValue.contains("709") || outputGammaValue.contains("gamma") -> "SDR"
    else -> "Unknown"
  }

  return VideoFormatStatus(sourceLabel = sourceLabel, outputLabel = outputLabel)
}

@Composable
fun VideoFormatStatusRow(
  status: VideoFormatStatus?,
  modifier: Modifier = Modifier,
) {
  if (status == null) return
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = "Source: ${status.sourceLabel}",
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.tertiary,
    )
    Text(
      text = "• Output: ${status.outputLabel}",
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.tertiary,
      modifier = Modifier.padding(end = 4.dp),
    )
  }
}
