/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.preferences.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.player.controls.components.glassPressFeedback

/** Background material never owns the click handler, dimensions or content semantics. */
@Composable
internal fun ThemeGlassButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
  content: @Composable RowScope.() -> Unit,
) {
  val glass = settingsGlassEnabled()
  val shape = RoundedCornerShape(26.dp)
  val interactionSource = remember { MutableInteractionSource() }
  MaterialButton(
    onClick = onClick,
    modifier = modifier.clip(shape).settingsGlassSurface(cornerRadius = 26.dp)
      .glassPressFeedback(interactionSource, enabled, glass = glass),
    enabled = enabled,
    shape = shape,
    colors = ButtonDefaults.buttonColors(
      containerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.primary,
      contentColor = if (glass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
    ),
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
  )
}

@Composable
internal fun ThemeGlassOutlinedButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
  content: @Composable RowScope.() -> Unit,
) {
  val glass = settingsGlassEnabled()
  val shape = RoundedCornerShape(26.dp)
  val interactionSource = remember { MutableInteractionSource() }
  MaterialOutlinedButton(
    onClick = onClick,
    modifier = modifier.clip(shape).settingsGlassSurface(cornerRadius = 26.dp)
      .glassPressFeedback(interactionSource, enabled, glass = glass),
    enabled = enabled,
    shape = shape,
    border = if (glass) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    colors = ButtonDefaults.outlinedButtonColors(
      containerColor = Color.Transparent,
      contentColor = if (glass) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
    ),
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
  )
}
