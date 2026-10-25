/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.player.controls.components.glassPressFeedback
import app.infinity.mpvz.ui.preferences.components.settingsGlassEnabled
import app.infinity.mpvz.ui.theme.glassRimLight

@Composable
fun GlassChip(
  selected: Boolean,
  onClick: () -> Unit,
  label: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
  shape: Shape? = null,
  colors: SelectableChipColors? = null,
) {
  val glass = settingsGlassEnabled()
  if (!glass) {
    FilterChip(selected = selected, onClick = onClick, label = label, modifier = modifier, enabled = enabled, leadingIcon = leadingIcon, trailingIcon = trailingIcon, shape = shape ?: FilterChipDefaults.shape, colors = colors ?: FilterChipDefaults.filterChipColors())
    return
  }
  val chipShape = shape ?: RoundedCornerShape(24.dp)
  val source = remember { MutableInteractionSource() }
  Surface(
    modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
      // Keep the press indication inside the pill instead of the layout bounds.
      .clip(chipShape)
      .glassPressFeedback(source, enabled)
      .semantics { this.selected = selected; this.role = Role.Checkbox }
      .clickable(interactionSource = source, role = Role.Checkbox, enabled = enabled, onClick = onClick),
    shape = chipShape,
    color = Color.Transparent,
    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .76f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .22f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
      horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
      leadingIcon?.invoke()
      label()
      trailingIcon?.invoke()
    }
  }
}
