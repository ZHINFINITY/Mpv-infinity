/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.preferences.components

/** Layout decisions shared by the theme editor action bar and its tests. */
object ThemeEditorLayoutPolicy {
  private const val NarrowWidthDp = 360f
  private const val MinimumBottomSpacingDp = 12f

  fun stackActions(maxWidthDp: Float): Boolean {
    require(maxWidthDp > 0f && maxWidthDp.isFinite()) { "maxWidthDp must be positive" }
    // Always keep Save/Reset/Cancel as three compact buttons in one row. Stacking
    // them full-width on phones wastes vertical space and is what the Appearance
    // editor screenshot reported.
    return false
  }

  fun bottomSpacingDp(navigationBarDp: Float, imeVisible: Boolean): Float {
    require(navigationBarDp >= 0f && navigationBarDp.isFinite()) {
      "navigationBarDp must be finite and non-negative"
    }
    return maxOf(MinimumBottomSpacingDp, navigationBarDp) + if (imeVisible) 8f else 0f
  }
}
