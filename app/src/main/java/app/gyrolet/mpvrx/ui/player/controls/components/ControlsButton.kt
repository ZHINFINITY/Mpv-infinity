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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.ui.theme.spacing
import org.koin.compose.koinInject

@Suppress("ModifierClickableOrder")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlsButton(
  icon: AppIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  title: String? = null,
  color: Color? = null,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val appearancePreferences = koinInject<AppearancePreferences>()
  val hideBackground by appearancePreferences.hidePlayerButtonsBackground.collectAsState()

  val clickEvent = LocalPlayerButtonsClickEvent.current
  PlayerGlassSurface(
    modifier =
      modifier
        .clip(CircleShape)
        .combinedClickable(
          onClick = {
            clickEvent()
            onClick()
          },
          onLongClick = onLongClick,
          interactionSource = interactionSource,
          indication = ripple(),
        ),
    shape = CircleShape,
    hideBackground = hideBackground,
    contentColor = color ?: MaterialTheme.colorScheme.onSurface,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = title,
      tint = color ?: MaterialTheme.colorScheme.onSurface,
      modifier =
        Modifier
          .padding(MaterialTheme.spacing.small)
          .size(20.dp),
    )
  }
}

@Composable
fun ControlsGroup(
  modifier: Modifier = Modifier,
  hideBackground: Boolean = false,
  content: @Composable RowScope.() -> Unit,
) {
  val spacing = MaterialTheme.spacing

  PlayerGlassSurface(
    modifier = modifier,
    shape = CircleShape,
    hideBackground = hideBackground,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Row(
      modifier = androidx.compose.ui.Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement =
      androidx.compose.foundation.layout.Arrangement
        .spacedBy(spacing.extraSmall),
      content = content,
    )
  }
}

@Preview
@Composable
private fun PreviewControlsButton() {
  ControlsButton(
    Icons.RoundedFilled.CatchingPokemon,
    onClick = {},
  )
}
