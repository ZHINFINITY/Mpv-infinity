/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fixed geometry, selected by material mode rather than by runtime shader support. */
data class BrowserLayoutGeometry(
  val edgeInset: Dp,
  val gridGap: Dp,
  val listGap: Dp,
  val listItemVerticalInset: Dp,
)

object BrowserLayoutPolicy {
  fun geometry(glassEnabled: Boolean): BrowserLayoutGeometry =
    // Glass changes optics only; browser layout remains the reference geometry.
    BrowserLayoutGeometry(8.dp, 4.dp, 0.dp, 0.dp)

  fun gridPadding(geometry: BrowserLayoutGeometry, top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues =
    PaddingValues(start = geometry.edgeInset, top = top, end = geometry.edgeInset, bottom = bottom)

  fun listPadding(geometry: BrowserLayoutGeometry, top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues =
    PaddingValues(start = geometry.edgeInset, top = top, end = geometry.edgeInset, bottom = bottom)

  /** Parent-owned list spacing must not add a second row inset. */
  fun musicRowPadding(parentOwnsSpacing: Boolean): PaddingValues =
    if (parentOwnsSpacing) PaddingValues(0.dp)
    else PaddingValues(horizontal = 16.dp, vertical = 12.dp)
}
