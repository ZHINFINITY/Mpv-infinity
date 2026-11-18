/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.R
import app.infinity.mpvz.ui.player.SubtitleLane
import app.infinity.mpvz.ui.theme.spacing

@Composable
internal fun SubtitleLaneSelector(
  selected: SubtitleLane,
  onSelected: (SubtitleLane) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    SubtitleLane.values().forEach { lane ->
      val labelRes = if (lane == SubtitleLane.Primary) R.string.player_sheets_sub_primary else R.string.player_sheets_sub_secondary
      FilterChip(
        selected = selected == lane,
        onClick = { onSelected(lane) },
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}
