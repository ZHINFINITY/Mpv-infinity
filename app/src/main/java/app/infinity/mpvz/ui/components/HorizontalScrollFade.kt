/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class HorizontalScrollFadeVisibility(
  val start: Boolean,
  val end: Boolean,
)

/** Returns which physical viewport edges hide content at the current scroll offset. */
internal fun horizontalScrollFadeVisibility(
  value: Int,
  maxValue: Int,
  reverseScrolling: Boolean,
): HorizontalScrollFadeVisibility {
  if (maxValue <= 0) return HorizontalScrollFadeVisibility(start = false, end = false)
  val offset = value.coerceIn(0, maxValue)
  return if (reverseScrolling) {
    HorizontalScrollFadeVisibility(start = offset < maxValue, end = offset > 0)
  } else {
    HorizontalScrollFadeVisibility(start = offset > 0, end = offset < maxValue)
  }
}

/** Fades the clipped edge(s) only when horizontal content overflows its viewport. */
fun Modifier.horizontalScrollFadeEdges(
  scrollState: ScrollState,
  reverseScrolling: Boolean = false,
  fadeWidth: Dp = 14.dp,
): Modifier =
  graphicsLayer {
    compositingStrategy =
      if (scrollState.maxValue > 0) {
        CompositingStrategy.Offscreen
      } else {
        CompositingStrategy.Auto
      }
  }.drawWithCache {
    val widthPx = size.width.coerceAtLeast(1f)
    val fadeFraction = (fadeWidth.toPx() / widthPx).coerceIn(0.01f, 0.32f)

    fun edgeMask(fadeStart: Boolean, fadeEnd: Boolean): Brush {
      val stops = buildList {
        add(0f to if (fadeStart) Color.Transparent else Color.White)
        if (fadeStart) add(fadeFraction to Color.White)
        if (fadeEnd) add((1f - fadeFraction) to Color.White)
        add(1f to if (fadeEnd) Color.Transparent else Color.White)
      }
      return Brush.horizontalGradient(colorStops = stops.toTypedArray())
    }

    val startMask = edgeMask(fadeStart = true, fadeEnd = false)
    val endMask = edgeMask(fadeStart = false, fadeEnd = true)
    val bothMask = edgeMask(fadeStart = true, fadeEnd = true)

    onDrawWithContent {
      drawContent()
      if (scrollState.maxValue > 0 && size.width > 0f) {
        val visibility = horizontalScrollFadeVisibility(scrollState.value, scrollState.maxValue, reverseScrolling)
        val mask =
          when {
            visibility.start && visibility.end -> bothMask
            visibility.start -> startMask
            visibility.end -> endMask
            else -> null
          }
        mask?.let { drawRect(brush = it, blendMode = BlendMode.DstIn) }
      }
    }
  }
