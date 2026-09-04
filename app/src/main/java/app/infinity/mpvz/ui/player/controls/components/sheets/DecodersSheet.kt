/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.player.Decoder
import app.infinity.mpvz.ui.player.PlaybackEngineMode
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.RendererBackendPolicy

@Composable
fun DecodersSheet(
  selectedDecoder: Decoder,
  onSelect: (Decoder) -> Unit,
  selectedEngine: PlaybackEngineMode = PlaybackEngineMode.MPV,
  onSelectEngine: (PlaybackEngineMode) -> Unit = {},
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
      item(key = "engine-mpv") {
        AudioTrackRow(
          title = "MPV",
          isSelected = selectedEngine == PlaybackEngineMode.MPV,
          onClick = { onSelectEngine(PlaybackEngineMode.MPV) },
        )
      }
      item(key = "engine-media3") {
        AudioTrackRow(
          title = "Google Media3",
          isSelected = selectedEngine == PlaybackEngineMode.MEDIA3,
          onClick = { onSelectEngine(PlaybackEngineMode.MEDIA3) },
        )
      }
      items(Decoder.entries.minusElement(Decoder.Auto), key = { it.name }) { decoder ->
        AudioTrackRow(
          title = stringResource(R.string.player_sheets_decoder_formatted, decoder.title, decoder.value),
          isSelected = selectedDecoder == decoder,
          enabled = decoder != Decoder.HWPlus || directMediaCodecAllowed,
          onClick = { onSelect(decoder) },
        )
      }
    }
  }
}
