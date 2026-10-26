/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.preferences.components


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.player.controls.components.GlassSwitch
import app.infinity.mpvz.ui.components.ControlRowSpacing

@Composable
fun SwitchPreference(
  value: Boolean,
  onValueChange: (Boolean) -> Unit,
  title: @Composable () -> Unit,
  summary: @Composable (() -> Unit)? = null,
  icon: @Composable (() -> Unit)? = null,
  enabled: Boolean = true,
  titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
  summaryStyle: TextStyle = MaterialTheme.typography.bodyMedium,
  switchModifier: Modifier = Modifier,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .settingsGlassSurface(cornerRadius = 18.dp)
        .clickable(enabled = enabled) { onValueChange(!value) }
        .padding(start = ControlRowSpacing.StartPadding, end = ControlRowSpacing.EndPadding, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Box(
        modifier = Modifier.padding(end = 16.dp),
        contentAlignment = Alignment.Center,
      ) {
        icon()
      }
    }

    Column(
      modifier =
        Modifier
          .weight(1f)
          .padding(end = ControlRowSpacing.TextToControlGap),
    ) {
      ProvideTextStyle(value = titleStyle) {
        title()
      }
      if (summary != null) {
        ProvideTextStyle(value = summaryStyle.copy(color = MaterialTheme.colorScheme.outline)) {
          summary()
        }
      }
    }

    GlassSwitch(
      checked = value,
      onCheckedChange = onValueChange,
      enabled = enabled,
      modifier = switchModifier,
    )
  }
}
