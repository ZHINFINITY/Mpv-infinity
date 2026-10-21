/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.icons.AppIcon
import app.infinity.mpvz.ui.icons.Icon
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.controls.LocalPlayerButtonsClickEvent
import app.infinity.mpvz.ui.theme.LocalDarkAppColorScheme
import app.infinity.mpvz.ui.theme.spacing
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import org.koin.compose.koinInject

@Suppress("CompositionLocalAllowlist")
internal val LocalForceDarkPlayerButtonsBackground = staticCompositionLocalOf { false }

@Suppress("CompositionLocalAllowlist")
internal val LocalHidePlayerButtonsBackground = staticCompositionLocalOf { false }

@Composable
private fun playerButtonColorScheme(
  forceDark: Boolean =
    LocalForceDarkPlayerButtonsBackground.current && !LocalHidePlayerButtonsBackground.current,
): ColorScheme =
  if (forceDark) LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme else MaterialTheme.colorScheme

@Composable
internal fun playerButtonContainerColor(): Color =
  playerButtonColorScheme().surfaceContainer.copy(alpha = 0.55f)

@Composable
internal fun playerButtonContentColor(): Color = playerButtonColorScheme().onSurface

@Composable
internal fun playerButtonBorderColor(): Color =
  playerButtonColorScheme().outlineVariant.copy(alpha = 0.4f)

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
  enabled: Boolean = true,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hideBackground = LocalHidePlayerButtonsBackground.current
  val appearance = koinInject<AppearancePreferences>()
  val liquidGlassSurfaces by appearance.liquidGlassSurfaces.collectAsState()
  val resolvedColor = color ?: playerButtonContentColor()
  val glassEnabled = liquidGlassSurfaces

  val clickEvent = LocalPlayerButtonsClickEvent.current
  Surface(
    modifier =
      modifier
        .clip(CircleShape)
        .playerGlassPane(enabled = glassEnabled, opacity = 0.88f, highlight = 0.18f, cornerRadius = 999.dp)
        .combinedClickable(
          enabled = enabled,
          onClick = {
            clickEvent()
            onClick()
          },
          onLongClick = {
            clickEvent()
            onLongClick()
          },
          interactionSource = interactionSource,
          indication = ripple(),
        ),
    shape = CircleShape,
    color = if (glassEnabled || hideBackground) Color.Transparent else playerButtonContainerColor(),
    contentColor = resolvedColor,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border =
      if (hideBackground || glassEnabled) {
        null
      } else {
        BorderStroke(
          1.dp,
          playerButtonBorderColor(),
        )
      },
  ) {
    Icon(
      imageVector = icon,
      contentDescription = title,
      tint = if (enabled) resolvedColor else resolvedColor.copy(alpha = 0.38f),
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
  content: @Composable RowScope.() -> Unit,
) {
  val spacing = MaterialTheme.spacing

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement =
      androidx.compose.foundation.layout.Arrangement
        .spacedBy(spacing.extraSmall),
    content = content,
  )
}

@Preview
@Composable
private fun PreviewControlsButton() {
  ControlsButton(
    Icons.RoundedFilled.CatchingPokemon,
    onClick = {},
  )
}
