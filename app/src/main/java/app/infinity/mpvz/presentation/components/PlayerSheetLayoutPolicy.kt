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
}
