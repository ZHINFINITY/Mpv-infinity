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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerControlsStyle
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import org.koin.compose.koinInject

/**
 * A player control surface that follows the selected player-controls theme.
 * Keeping the switch here makes the preference apply consistently to portrait
 * and landscape controls, headers, groups, and the seekbar rail.
 */
@Composable
fun PlayerGlassSurface(
  modifier: Modifier = Modifier,
  shape: Shape = CircleShape,
  hideBackground: Boolean = false,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  content: @Composable () -> Unit,
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val playerControlsStyle by appearancePreferences.playerControlsStyle.collectAsState()

  if (playerControlsStyle == PlayerControlsStyle.Glass) {
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
  } else {
    Surface(
      modifier = modifier,
      shape = shape,
      color =
        if (hideBackground) {
          Color.Transparent
        } else {
          MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
        },
      contentColor = contentColor,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border =
        if (hideBackground) {
          null
        } else {
          BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
          )
        },
      content = content,
    )
  }
}
