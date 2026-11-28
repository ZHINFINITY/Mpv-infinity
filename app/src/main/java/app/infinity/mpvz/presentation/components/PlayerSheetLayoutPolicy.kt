/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.presentation.components

/**
 * Bounds used by [PlayerSheet]. Insets are consumed before this limit is applied
 * so the glass surface and the visible sheet always have the same measured size.
 */
object PlayerSheetLayoutPolicy {
  fun maxHeightDp(
    screenHeightDp: Int,
    portrait: Boolean,
    imeVisible: Boolean,
    customMaxHeightDp: Float?,
  ): Float {
    require(screenHeightDp > 0) { "screenHeightDp must be positive" }
    return customMaxHeightDp?.takeIf { it.isFinite() && it > 0f }
      ?: when {
        imeVisible -> screenHeightDp.toFloat()
        portrait -> screenHeightDp * 0.90f
        else -> screenHeightDp.toFloat()
      }
  }

  /** Height the player needs above a track list so the playing album art stays uncovered. */
  const val LIST_SHEET_CHROME_DP = 104f

  /**
   * Cap for a scrollable track list inside a player sheet. Deliberately well under
   * [maxHeightDp] so a long artist or playlist list cannot grow over the album art.
   */
  fun listMaxHeightDp(screenHeightDp: Int): Float {
    require(screenHeightDp > 0) { "screenHeightDp must be positive" }
    return (screenHeightDp * 0.34f).coerceIn(160f, 320f)
  }

  fun listSheetMaxHeightDp(screenHeightDp: Int): Float =
    listMaxHeightDp(screenHeightDp) + LIST_SHEET_CHROME_DP
}
