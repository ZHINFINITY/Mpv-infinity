/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.components.horizontalScrollFadeEdges

/**
 * A real, bounded viewport for player actions. horizontalScroll measures its child with an
 * unbounded width; the child minimum keeps short rows centered/end-aligned and lets long rows grow
 * to their natural width instead of reflowing or compressing individual controls.
 */
@Composable
internal fun PlayerControlScrollStrip(
  modifier: Modifier = Modifier,
  scrollState: ScrollState = rememberScrollState(),
  gap: Dp = 8.dp,
  alignment: Alignment.Horizontal = Alignment.Start,
  reverseScrolling: Boolean = false,
  showEdgeFades: Boolean = false,
  content: @Composable RowScope.() -> Unit,
) {
  val viewportModifier = modifier.fillMaxWidth().clipToBounds()
  val fadedViewportModifier =
    if (showEdgeFades) viewportModifier.horizontalScrollFadeEdges(scrollState, reverseScrolling)
    else viewportModifier

  BoxWithConstraints(modifier = fadedViewportModifier) {
    Row(
      modifier = Modifier.horizontalScroll(scrollState, reverseScrolling = reverseScrolling).widthIn(min = maxWidth),
      horizontalArrangement = Arrangement.spacedBy(gap, alignment),
      verticalAlignment = Alignment.CenterVertically,
      content = content,
    )
  }
}
