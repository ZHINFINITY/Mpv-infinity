/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Independent static glass styling. No screen capture, live blur, or refraction. */
@Composable
fun GlassSurface(
  modifier: Modifier = Modifier,
  enabled: Boolean,
  opacity: Float = 0.88f,
  highlight: Float = 0.18f,
  cornerRadius: Dp = 24.dp,
  content: @Composable () -> Unit,
) {
  val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
  val base = MaterialTheme.colorScheme.surfaceContainerHigh
  val alpha = GlassSurfacePolicy.opacity(opacity)
  val glow = GlassSurfacePolicy.highlight(highlight)
  val fill = remember(enabled, base, alpha, glow) {
    if (enabled) {
      val top = Color.White.copy(alpha = glow * 0.35f).compositeOver(base.copy(alpha = alpha))
      Brush.verticalGradient(listOf(top, base.copy(alpha = alpha)))
    } else {
      Brush.verticalGradient(listOf(base, base))
    }
  }
  Box(
    modifier = modifier
      .clip(shape)
      .background(fill)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) glow + 0.18f else 0.25f), shape),
  ) { content() }
}
