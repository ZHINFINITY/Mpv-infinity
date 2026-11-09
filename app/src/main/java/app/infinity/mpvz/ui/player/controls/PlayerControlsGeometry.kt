/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared transport sizing for the runtime player and its settings preview. */
object PlayerControlsGeometry {
  /** Keep side transports at the minimum comfortable touch size while reducing their visual weight. */
  val PlaylistSkipButtonSize = 48.dp
  val PlayPauseButtonSize = 56.dp
  val PlaylistButtonGap = 16.dp

  /** Reserve the full three-button pill slot before frame navigation begins expanding. */
  fun frameNavigationSlotWidth(buttonSize: Dp, compact: Boolean): Dp =
    if (compact) buttonSize else buttonSize * 3f

  /** Keep the time/network pill's expansion from resizing the surrounding control row. */
  fun timeNetworkSlotWidth(buttonSize: Dp, compact: Boolean): Dp =
    if (compact) buttonSize else maxOf(buttonSize, 176.dp)
}
