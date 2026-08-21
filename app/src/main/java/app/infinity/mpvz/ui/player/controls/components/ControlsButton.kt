/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.controls.components

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
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.PlayerControlsStyle
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.controls.LocalPlayerButtonsClickEvent
import app.infinity.mpvz.ui.theme.spacing
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
  val playerControlsStyle by appearancePreferences.playerControlsStyle.collectAsState()
  val glassTheme =
    playerControlsStyle == PlayerControlsStyle.Glass || playerControlsStyle == PlayerControlsStyle.Glossy
  val playerContentColor = MaterialTheme.colorScheme.onSurface
  val clickEvent = LocalPlayerButtonsClickEvent.current
  val iconContent: @Composable () -> Unit = {
    Icon(
      imageVector = icon,
      contentDescription = title,
      tint = color ?: playerContentColor,
      modifier =
        Modifier
          .padding(MaterialTheme.spacing.small)
          .size(20.dp),
    )
  }
  val buttonModifier =
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
      )

  if (glassTheme) {
    PlayerGlassSurface(
      modifier = buttonModifier,
      shape = CircleShape,
      hideBackground = hideBackground,
      contentColor = color ?: playerContentColor,
      content = iconContent,
    )
  } else {
    Surface(
      modifier = buttonModifier,
      shape = CircleShape,
      color =
        if (hideBackground) {
          Color.Transparent
        } else {
          MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
        },
      contentColor = color ?: playerContentColor,
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
      content = iconContent,
    )
  }
}

@Composable
fun ControlsGroup(
  modifier: Modifier = Modifier,
  hideBackground: Boolean = false,
  glassTheme: Boolean = true,
  content: @Composable RowScope.() -> Unit,
) {
  val spacing = MaterialTheme.spacing
  val appearancePreferences = koinInject<AppearancePreferences>()
  val playerControlsStyle by appearancePreferences.playerControlsStyle.collectAsState()
  val useGlassTheme =
    glassTheme &&
      (playerControlsStyle == PlayerControlsStyle.Glass || playerControlsStyle == PlayerControlsStyle.Glossy)

  if (useGlassTheme) {
    PlayerGlassSurface(
      modifier = modifier,
      shape = CircleShape,
      hideBackground = hideBackground,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
          androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.extraSmall),
        content = content,
      )
    }
  } else {
    Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement =
        androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.extraSmall),
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
