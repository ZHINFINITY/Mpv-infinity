/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

internal object VideoFlipDecoderPolicy {
  fun requiresHwOrSwDecoder(
    activeEngine: PlaybackEngineMode,
    activeDecoder: String?,
  ): Boolean {
    if (activeEngine != PlaybackEngineMode.MPV) return false
    val decoderName =
      activeDecoder
        ?.trim()
        ?.substringBefore(',')
        ?.substringBefore(' ')
    return decoderName?.equals(Decoder.HWPlus.value, ignoreCase = true) == true
  }
}
