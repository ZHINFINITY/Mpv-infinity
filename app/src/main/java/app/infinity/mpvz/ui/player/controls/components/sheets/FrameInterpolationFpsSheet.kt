/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.MPVFLOW_TARGET_FPS_OPTIONS
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.player.controls.components.interpolationControlsAvailable
import org.koin.compose.koinInject

@Composable
fun FrameInterpolationFpsSheet(activity: PlayerActivity, onDismissRequest: () -> Unit) {
  val preferences = koinInject<DecoderPreferences>()
  val selected by preferences.mpvFlowTargetFps.collectAsState()
  val available = interpolationControlsAvailable(activity)
  PlayerSheet(onDismissRequest = onDismissRequest) {
    Column(
      modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().selectableGroup().padding(vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        stringResource(R.string.pref_decoder_mpvflow_target_fps_title),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        stringResource(R.string.player_interpolation_next_playback_note),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      MPVFLOW_TARGET_FPS_OPTIONS.forEach { fps ->
        Row(
          modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected == fps, enabled = available, role = Role.RadioButton) {
              onDismissRequest()
              if (preferences.mpvFlowTargetFps.get() != fps) {
                preferences.mpvFlowTargetFps.set(fps)
                activity.applyInterpolationPreferencesFromControls()
              }
            }
            .padding(horizontal = 24.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
          RadioButton(selected = selected == fps, onClick = null, enabled = available)
          Text(stringResource(R.string.pref_decoder_mpvflow_target_fps_value, fps))
        }
      }
    }
  }
}
