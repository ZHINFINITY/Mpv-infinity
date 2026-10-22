/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.ui.theme.spacing

/** Inset, translucent material for a standalone player-sheet setting row. */
@Composable
fun Modifier.playerSheetSettingRow(active: Boolean = false): Modifier {
  val shape = RoundedCornerShape(16.dp)
  val onSurface = MaterialTheme.colorScheme.onSurface
  val accent = MaterialTheme.colorScheme.primary
  return this
    .fillMaxWidth()
    .padding(horizontal = MaterialTheme.spacing.medium)
    .clip(shape)
    .background(
      if (active) accent.copy(alpha = 0.16f) else onSurface.copy(alpha = 0.07f),
      shape,
    )
    .border(
      width = 1.dp,
      color = if (active) accent.copy(alpha = 0.5f) else onSurface.copy(alpha = 0.14f),
      shape = shape,
    )
    .padding(horizontal = MaterialTheme.spacing.medium, vertical = 12.dp)
}
