/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.player.Decoder
import app.infinity.mpvz.ui.player.PlaybackEngineMode
import app.infinity.mpvz.ui.player.PlaybackSession
import app.infinity.mpvz.ui.player.RendererBackendPolicy
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import org.koin.compose.koinInject

@Composable
fun DecodersSheet(
  selectedDecoder: Decoder,
  onSelect: (Decoder) -> Unit,
  selectedEngine: PlaybackEngineMode = PlaybackEngineMode.MPV,
  onSelectEngine: (PlaybackEngineMode) -> Unit = {},
  onDismissRequest: () -> Unit,
) {
  val gpuApi by PlaybackSession.propString["gpu-api"].collectAsState()
  val decoderPreferences = koinInject<DecoderPreferences>()
  val mpvFlowQuality by decoderPreferences.mpvFlowQuality.collectAsState()
  val mpvFlowProfile = (mpvFlowQuality.coerceIn(0, 100) * 9f / 100f).roundToInt()
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
        item(key = "mpvflow-quality") {
          Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
              Text("Frame interpolation")
              Text(
                text = "Profile ${mpvFlowProfile + 1}/10 · " +
                  if (mpvFlowProfile < 3) "Performance" else if (mpvFlowProfile > 6) "Quality" else "Balanced",
                modifier = Modifier.padding(start = 8.dp),
              )
            }
            Slider(
              value = mpvFlowProfile.toFloat(),
              onValueChange = { profile ->
                decoderPreferences.mpvFlowQuality.set((profile.roundToInt() * 100 / 9f).roundToInt())
              },
              valueRange = 0f..9f,
              steps = 8,
              enabled = isVulkanActive,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
              Text("Performance")
              Text("Quality", modifier = Modifier.padding(start = 8.dp))
            }
          }
        }
        if (selectedTab != 2 && isVulkanActive && !directMediaCodecAllowed) {
          item(key = "hwplus-unavailable-info") {
            Text(
              "HW+ is unavailable in this Vulkan build because direct MediaCodec-to-Vulkan import " +
                "is not enabled. HW still uses hardware decoding through a copy path.",
            )
          }
        }
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
