/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * A dark, translucent control surface intended to remain legible over changing video frames.
 * Keeping this in one place makes the player theme consistent across portrait and landscape.
 */
@Composable
fun PlayerGlassSurface(
  modifier: Modifier = Modifier,
  shape: Shape = CircleShape,
  hideBackground: Boolean = false,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  content: @Composable () -> Unit,
) {
  val decoratedModifier =
    if (hideBackground) {
      modifier
    } else {
      modifier.background(
        brush =
          Brush.linearGradient(
            colors =
              listOf(
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.04f),
              ),
          ),
        shape = shape,
      )
    }

  Surface(
    modifier = decoratedModifier,
    shape = shape,
    color = if (hideBackground) Color.Transparent else Color.Black.copy(alpha = 0.30f),
    contentColor = contentColor,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border =
      if (hideBackground) {
        null
      } else {
        BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
      },
    content = content,
  )
}
