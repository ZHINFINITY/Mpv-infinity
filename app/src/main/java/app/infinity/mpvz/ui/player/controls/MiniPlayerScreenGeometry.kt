/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow

/** MainActivity and PlayerActivity have distinct content/window origins. */
internal fun LayoutCoordinates.miniPlayerScreenBounds(host: View): Rect {
  val screen = IntArray(2)
  val window = IntArray(2)
  host.getLocationOnScreen(screen)
  host.getLocationInWindow(window)
  return boundsInWindow().translate(Offset(
    (screen[0] - window[0]).toFloat(),
    (screen[1] - window[1]).toFloat(),
  ))
}
