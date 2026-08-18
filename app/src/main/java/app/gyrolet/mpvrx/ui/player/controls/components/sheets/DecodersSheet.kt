/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlaybackEngineMode
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.Decoder
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.RendererBackendPolicy

@Composable
fun DecodersSheet(
  selectedDecoder: Decoder,
  isMedia3Active: Boolean = false,
  engineSelection: PlaybackEngineMode = if (isMedia3Active) PlaybackEngineMode.Media3 else PlaybackEngineMode.MPV,
  onEngineSelected: (PlaybackEngineMode) -> Unit = {},
  media3DecoderName: String? = null,
  onSelect: (Decoder) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val gpuApi by PlaybackSession.propString["gpu-api"].collectAsState()
  val isVulkanActive = gpuApi == "vulkan"
  val directMediaCodecAllowed =
    RendererBackendPolicy.canUseDirectMediaCodec(
      usesVulkan = isVulkanActive,
      buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
    )

  PlayerSheet(onDismissRequest) {
    LazyColumn {
      item {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          Text(
            text = "Playback engine",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
          )
          Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
          ) {
            Row(modifier = Modifier.fillMaxWidth()) {
              AudioTrackRow(
                title = "MPV",
                isSelected = engineSelection == PlaybackEngineMode.MPV,
                onClick = {
                  onEngineSelected(PlaybackEngineMode.MPV)
                  onDismissRequest()
                },
                textColor = Color.White,
                modifier = Modifier.weight(1f),
              )
              AudioTrackRow(
                title = "Media3",
                isSelected = engineSelection == PlaybackEngineMode.Media3,
                onClick = {
                  onEngineSelected(PlaybackEngineMode.Media3)
                  onDismissRequest()
                },
                textColor = Color.White,
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
      if (isMedia3Active) {
        item {
          val mode =
            if (media3DecoderName?.contains("sw", ignoreCase = true) == true) "SW" else "HW"
          AudioTrackRow(
            title = "Media3 · $mode",
            isSelected = true,
            enabled = false,
            onClick = {},
            textColor = Color.White,
          )
        }
      } else {
        items(Decoder.entries, key = { it.name }) { decoder ->
          AudioTrackRow(
            title = stringResource(R.string.player_sheets_decoder_formatted, decoder.title, decoder.value),
            isSelected = selectedDecoder == decoder,
            enabled = decoder != Decoder.HWPlus || directMediaCodecAllowed,
            onClick = { onSelect(decoder) },
            textColor = Color.White,
          )
        }
      }
    }
  }
}
