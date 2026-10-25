/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.panels

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal enum class SideSheetCorner { TopStart, TopEnd, BottomStart, BottomEnd }

internal enum class SideSheetEdge { Top, Bottom, Start, End }

/** Distance, in px, from the panel host's bounds to the root window edges. */
internal data class EdgeBleed(val top: Int, val bottom: Int, val end: Int) {
  companion object {
    val Zero = EdgeBleed(0, 0, 0)
  }
}

/**
 * Build 1933 geometry contract for the two side-opening player panels the owner asked to be
 * "part of the screen": the grid More drawer and the Subtitles settings panel. In landscape
 * their glass reaches the top, bottom and end (right in LTR) display edges, only the free inner
 * corners stay rounded, and content keeps clear of system bars and the display cutout.
 *
 * Every other sheet and panel keeps its pre-1932 placement, so attachment is strictly opt-in.
 */
internal object EdgeAttachedPanelPolicy {
  const val InnerCornerRadiusDp = 24f

  /** Attachment is opt-in per panel and landscape-only; portrait keeps the centred card. */
  fun attaches(requested: Boolean, portrait: Boolean): Boolean = requested && !portrait

  fun attachedEdges(attached: Boolean): Set<SideSheetEdge> =
    if (attached) setOf(SideSheetEdge.Top, SideSheetEdge.Bottom, SideSheetEdge.End) else emptySet()

  fun innerCorners(layoutDirection: LayoutDirection): Set<SideSheetCorner> =
    if (layoutDirection == LayoutDirection.Ltr) {
      setOf(SideSheetCorner.TopStart, SideSheetCorner.BottomStart)
    } else {
      setOf(SideSheetCorner.TopEnd, SideSheetCorner.BottomEnd)
    }

  fun shape(layoutDirection: LayoutDirection): RoundedCornerShape =
    if (layoutDirection == LayoutDirection.Ltr) {
      RoundedCornerShape(
        topStart = InnerCornerRadiusDp.dp,
        bottomStart = InnerCornerRadiusDp.dp,
        topEnd = 0.dp,
        bottomEnd = 0.dp,
      )
    } else {
      RoundedCornerShape(
        topStart = 0.dp,
        bottomStart = 0.dp,
        topEnd = InnerCornerRadiusDp.dp,
        bottomEnd = InnerCornerRadiusDp.dp,
      )
    }

  /**
   * How far the panel host sits from the root window's top, bottom and end edges. The host's
   * bounds are given in root coordinates. Negative distances (host translated past the edge
   * during a slide animation) clamp to zero.
   */
  fun bleed(
    hostLeft: Int,
    hostTop: Int,
    hostRight: Int,
    hostBottom: Int,
    rootWidth: Int,
    rootHeight: Int,
    layoutDirection: LayoutDirection,
  ): EdgeBleed {
    require(rootWidth >= 0 && rootHeight >= 0) { "Invalid root size" }
    val end = if (layoutDirection == LayoutDirection.Ltr) rootWidth - hostRight else hostLeft
    return EdgeBleed(
      top = hostTop.coerceAtLeast(0),
      bottom = (rootHeight - hostBottom).coerceAtLeast(0),
      end = end.coerceAtLeast(0),
    )
  }

  /**
   * A slide-in moves the host towards the edge, which temporarily shrinks the measured end
   * bleed. Keep the largest value seen for the current window geometry so the glass stays
   * pinned to the edge and the panel does not relayout on every animation frame.
   */
  fun settle(previous: EdgeBleed, measured: EdgeBleed): EdgeBleed =
    EdgeBleed(
      top = maxOf(previous.top, measured.top),
      bottom = maxOf(previous.bottom, measured.bottom),
      end = maxOf(previous.end, measured.end),
    )

  /** Content inset per edge: never closer to the display edge than the bleed or the safe area. */
  fun contentInset(bleedPx: Int, safeInsetPx: Int): Int =
    maxOf(bleedPx.coerceAtLeast(0), safeInsetPx.coerceAtLeast(0))

  /** An attached panel is part of the screen edge, so it is not draggable away from it. */
  fun dragRange(attached: Boolean, freeSpacePx: Float): ClosedFloatingPointRange<Float> =
    if (attached) 0f..0f else -freeSpacePx.coerceAtLeast(0f)..0f
}
