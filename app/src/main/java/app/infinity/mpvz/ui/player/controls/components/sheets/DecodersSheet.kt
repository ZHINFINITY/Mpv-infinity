/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.player.Decoder
import app.infinity.mpvz.ui.player.DecoderUiPolicy
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
  val directMediaCodecAllowed = RendererBackendPolicy.canUseDirectMediaCodec(
    usesVulkan = isVulkanActive,
    buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
  )
  val tabs = DecoderUiPolicy.playerEngineTabs()
  val selectedTab = tabs.indexOf(selectedEngine).coerceAtLeast(0)


  PlayerSheet(onDismissRequest) {
    Column {
      ScrollableTabRow(
        selectedTabIndex = selectedTab,
        // PlayerSheet owns the glass/backdrop. The default tab-row surface is
        // opaque and produced the black full-width rectangle in the decoder sheet.
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        divider = {},
      ) {
        tabs.forEachIndexed { index, engine ->
          Tab(
            selected = selectedTab == index,
            onClick = { onSelectEngine(engine) },
            text = { Text(engine.title) },
          )
        }
      }
      LazyColumn {
        if (!directMediaCodecAllowed) {
          item(key = "hwplus-unavailable-info") {
            Text(
              "HW+ is unavailable in this Vulkan build because direct MediaCodec-to-Vulkan import " +
                "is not enabled. HW still uses hardware decoding through a copy path.",
            )
          }
        }
        if (selectedEngine == PlaybackEngineMode.NATIVE) {
          item(key = "native-active") {
            AudioTrackRow(
              title = "Native playback engine active",
              isSelected = true,
              onClick = {},
            )
          }
        } else {
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
  }
}

private const val NATIVE_ENGINE_LABEL = "Native"

@Suppress("unused")
private fun nativeEngineLabel(): String = NATIVE_ENGINE_LABEL
