/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.preferences.components

/** Layout decisions shared by the theme editor action bar and its tests. */
object ThemeEditorLayoutPolicy {
  private const val MinimumBottomSpacingDp = 12f

  /** Reset, Cancel and Save always share one row; this is the gap between them. */
  const val ActionGapDp = 8f

  /** Labels keep the Material size when they fit and never shrink below this. */
  const val MinLabelSp = 11f
  private const val LabelStepSp = 0.5f

  /**
   * Next font size for a single-line button label, or null once it fits (or has reached
   * [MinLabelSp], after which the ellipsis is the last resort).
   */
  fun nextLabelSp(currentSp: Float, overflowing: Boolean): Float? {
    require(currentSp > 0f && currentSp.isFinite()) { "currentSp must be positive and finite" }
    if (!overflowing || currentSp <= MinLabelSp) return null
    return maxOf(MinLabelSp, currentSp - LabelStepSp)
  }

  fun bottomSpacingDp(navigationBarDp: Float, imeVisible: Boolean): Float {
    require(navigationBarDp >= 0f && navigationBarDp.isFinite()) {
      "navigationBarDp must be finite and non-negative"
    }
    return maxOf(MinimumBottomSpacingDp, navigationBarDp) + if (imeVisible) 8f else 0f
  }
}
