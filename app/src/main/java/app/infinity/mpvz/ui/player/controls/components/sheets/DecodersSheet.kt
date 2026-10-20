/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.R
import app.infinity.mpvz.presentation.components.PlayerSheet
import app.infinity.mpvz.ui.player.Decoder
import app.infinity.mpvz.ui.player.DecoderUiPolicy
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
  val savedProfile = DecoderUiPolicy.profileForQuality(mpvFlowQuality)
  var profile by remember(savedProfile) { mutableFloatStateOf(savedProfile.toFloat()) }
  val isVulkanActive = gpuApi == "vulkan"
  val directMediaCodecAllowed = RendererBackendPolicy.canUseDirectMediaCodec(
    usesVulkan = isVulkanActive,
    buildSupportsMediaCodecVulkan = BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN,
  )
  val tabs = DecoderUiPolicy.playerEngineTabs()
  val selectedTab = tabs.indexOf(selectedEngine).coerceAtLeast(0)


  PlayerSheet(onDismissRequest) {
    Column {
      ScrollableTabRow(selectedTabIndex = selectedTab) {
        tabs.forEachIndexed { index, engine ->
          Tab(
            selected = selectedTab == index,
            onClick = { onSelectEngine(engine) },
            text = { Text(engine.title) },
          )
        }
      }
      LazyColumn {
        item(key = "mpvflow-quality") {
          Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column {
              Text("Frame interpolation")
              Text(
                text = "Profile ${profile.toInt() + 1}/${DecoderUiPolicy.PROFILE_COUNT} · " +
                  DecoderUiPolicy.profileLabel(profile.toInt()),
              )
            }
            Text("Interpolation trades processing headroom for smoother motion; Vulkan is required.")
            Slider(
              value = profile,
              onValueChange = { profile = it },
              onValueChangeFinished = {
                decoderPreferences.mpvFlowQuality.set(DecoderUiPolicy.qualityForProfile(profile.toInt()))
              },
              valueRange = 0f..(DecoderUiPolicy.PROFILE_COUNT - 1).toFloat(),
              steps = DecoderUiPolicy.PROFILE_COUNT - 2,
              enabled = isVulkanActive,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text("Performance")
              Text("Quality")
            }
          }
        }
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
