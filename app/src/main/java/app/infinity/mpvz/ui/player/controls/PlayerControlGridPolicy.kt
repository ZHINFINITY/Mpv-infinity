/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

/** Pure geometry rules for the More drawer; kept independent from Compose measurement. */
object PlayerControlGridPolicy {
  const val columnCount: Int = 3

  fun panelWidthDp(availableWidth: Float): Float =
    if (availableWidth.isFinite()) availableWidth.coerceIn(0f, 380f) else 380f

  fun rowCount(itemCount: Int, columns: Int = columnCount): Int {
    require(itemCount >= 0) { "itemCount must not be negative" }
    require(columns > 0) { "columns must be positive" }
    return (itemCount + columns - 1) / columns
  }
}
