/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.components

import androidx.compose.ui.unit.dp

/**
 * One spacing rule for every row that ends in a control (toggle/switch, value badge, button):
 * the row keeps [EndPadding] between the control and the card/screen edge, the label text keeps
 * at least [TextToControlGap] before the control and never runs under it, and stacked items
 * inside one card (toggle row, action button, status text) are separated by [StackedGap].
 */
object ControlRowSpacing {
  val EndPadding = 16.dp
  val StartPadding = 16.dp
  val TextToControlGap = 16.dp
  val StackedGap = 12.dp
}
