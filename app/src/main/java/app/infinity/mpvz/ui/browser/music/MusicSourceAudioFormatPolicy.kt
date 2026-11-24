/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.music

import java.util.Locale

/** Parsed values supplied by the local-file MediaInfo source probe. */
internal data class MusicSourceAudioFormat(
  val bitDepth: Int?,
  val sampleRateHz: Int?,
  val isFloat: Boolean = false,
)

/**
 * Lossless labels are based only on container/source metadata. Decoder PCM precision is deliberately
 * not an input to this policy, so a 32-bit output buffer can never be presented as 32-bit source.
 */
internal object MusicSourceAudioFormatPolicy {
  private val losslessFormats =
    setOf("flac", "alac", "wav", "wave", "aiff", "aif", "ape", "wavpack", "wv", "tak", "dsf", "dff")

  fun fromSourceMetadata(
    bitDepthText: String?,
    sampleRateText: String?,
    formatProfileText: String? = null,
  ): MusicSourceAudioFormat? {
    val bitDepth =
      Regex("\\d+").find(bitDepthText.orEmpty())?.value?.toIntOrNull()
        ?.takeIf { it in 1..64 }
    val isFloat = formatProfileText.orEmpty().contains("float", ignoreCase = true) ||
      bitDepthText.orEmpty().contains("float", ignoreCase = true)
    val sampleRateHz = parseSampleRate(sampleRateText)
    return if (bitDepth == null && sampleRateHz == null) {
      null
    } else {
      MusicSourceAudioFormat(bitDepth, sampleRateHz, isFloat)
    }
  }

  fun isLosslessCodec(codecOrExtension: String?): Boolean {
    val value = codecOrExtension.orEmpty().trim().lowercase(Locale.ROOT)
    if (value.isBlank()) return false
    val extension = value.substringAfterLast('.').substringBefore('?').substringBefore('#')
    return losslessFormats.any { format -> value == format || extension == format || value.contains(format) }
  }

  fun headerLabel(
    codecOrExtension: String?,
    sourceFormat: MusicSourceAudioFormat?,
  ): String? {
    if (!isLosslessCodec(codecOrExtension)) return null
    val details = buildList {
      sourceFormat?.bitDepth?.let { depth ->
        add(if (sourceFormat.isFloat) "$depth-bit float" else "$depth-bit")
      }
      sourceFormat?.sampleRateHz?.let { add(formatSampleRate(it)) }
    }
    return if (details.isEmpty()) "LOSSLESS" else "LOSSLESS · ${details.joinToString(" · ")}"
  }

  private fun parseSampleRate(text: String?): Int? {
    val normalized = text.orEmpty().trim().lowercase(Locale.ROOT)
      .replace(",", "")
      .replace(" ", "")
    if (normalized.isBlank()) return null
    val match = Regex("(\\d+(?:\\.\\d+)?)(khz|mhz|hz)?").find(normalized) ?: return null
    val number = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier =
      when (match.groupValues[2]) {
        "khz" -> 1_000.0
        "mhz" -> 1_000_000.0
        else -> 1.0
      }
    return (number * multiplier).toInt().takeIf { it in 1..768_000 }
  }

  private fun formatSampleRate(sampleRateHz: Int): String {
    val khz = sampleRateHz / 1_000.0
    return if (sampleRateHz % 1_000 == 0) {
      "${sampleRateHz / 1_000} kHz"
    } else {
      String.format(Locale.US, "%.1f kHz", khz)
    }
  }
}
