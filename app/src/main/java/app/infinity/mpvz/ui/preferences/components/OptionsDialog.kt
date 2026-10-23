/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.preferences.components

import app.infinity.mpvz.ui.components.GlassOptionSheet
import app.infinity.mpvz.ui.preferences.components.settingsGlassContainerColor

import app.infinity.mpvz.ui.preferences.components.settingsGlassSurface

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
fun <T> OptionsDialog(
  title: String,
  options: List<T>,
  selectedOption: T,
  onOptionSelected: (T) -> Unit,
  onDismiss: () -> Unit,
  optionLabel: @Composable (T) -> String,
) {
  GlassOptionSheet(
    onDismissRequest = onDismiss,
    title = { Text(text = title) },
    modifier = androidx.compose.ui.Modifier.settingsGlassSurface(cornerRadius = 28.dp, independent = true),
    containerColor = settingsGlassContainerColor(MaterialTheme.colorScheme.surfaceContainerHigh),
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.infinity.mpvz.R.string.generic_cancel),
        )
      }
    },
  ) {
    Column {
      HorizontalDivider()
      LazyColumn(
        contentPadding =
          androidx.compose.foundation.layout
            .PaddingValues(vertical = 8.dp),
        // Bounded by the sheet; long option lists scroll instead of clipping.
        modifier = Modifier.weight(1f, fill = false).selectableGroup(),
      ) {
        items(options, key = { option -> option?.hashCode() ?: System.identityHashCode(option) }) { option ->
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable { onOptionSelected(option) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = option == selectedOption,
              onClick = { onOptionSelected(option) },
            )
            Text(
              text = optionLabel(option),
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.padding(start = 16.dp),
            )
          }
        }
      }
      HorizontalDivider()
    }
  }
}
