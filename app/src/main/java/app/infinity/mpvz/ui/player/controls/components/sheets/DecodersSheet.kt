/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
  var selectedTab by remember(selectedEngine) {
    mutableIntStateOf(
      when (selectedEngine) {
        PlaybackEngineMode.AUTO -> 0
        PlaybackEngineMode.MPV -> 1
        PlaybackEngineMode.NATIVE -> 2
      },
    )
  }

  PlayerSheet(onDismissRequest) {
    Column {
      ScrollableTabRow(selectedTabIndex = selectedTab) {
        Tab(
          selected = selectedTab == 0,
          onClick = {
            selectedTab = 0
            onSelectEngine(PlaybackEngineMode.AUTO)
          },
          text = { Text("Auto") },
        )
        Tab(
          selected = selectedTab == 1,
          onClick = {
            selectedTab = 1
            onSelectEngine(PlaybackEngineMode.MPV)
          },
          text = { Text("MPV") },
        )
        Tab(
          selected = selectedTab == 2,
          onClick = {
            selectedTab = 2
            onSelectEngine(PlaybackEngineMode.NATIVE)
          },
          text = { Text("Native") },
        )
      }
      LazyColumn {
        if (selectedTab == 2) {
          item(key = "native-active") {
            AudioTrackRow(
              title = "Native playback engine active",
              isSelected = selectedEngine == PlaybackEngineMode.NATIVE,
              onClick = { onSelectEngine(PlaybackEngineMode.NATIVE) },
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
