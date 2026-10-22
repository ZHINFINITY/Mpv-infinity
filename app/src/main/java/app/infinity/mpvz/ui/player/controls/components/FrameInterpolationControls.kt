/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.BuildConfig
import app.infinity.mpvz.R
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.DecoderPreferences
import app.infinity.mpvz.preferences.MpvConfigControlledFeatures
import app.infinity.mpvz.preferences.PlayerControlActionPolicy
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.icons.Icons
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.player.Sheets
import app.infinity.mpvz.ui.player.controls.LocalPlayerButtonsClickEvent
import app.infinity.mpvz.ui.utils.isAnyMpvOptionOwnedByConfig
import app.infinity.mpvz.utils.device.VulkanCapabilities
import org.koin.compose.koinInject

/** The active backend, not the preferred engine or a saved toggle, determines apply timing. */
@Composable
internal fun interpolationApplyTimingNote(activity: PlayerActivity): String {
  val configOwned = isAnyMpvOptionOwnedByConfig(MpvConfigControlledFeatures.FRAME_INTERPOLATION)
  return stringResource(
    when {
      activity.isNativeEngineActive() -> R.string.interpolation_feedback_native
      configOwned -> R.string.interpolation_feedback_mpv_config
      else -> R.string.interpolation_feedback_mpv
    },
  )
}

@Composable
internal fun interpolationControlsAvailable(activity: PlayerActivity): Boolean {
  val preferences = koinInject<DecoderPreferences>()
  val enabled by preferences.mpvFlowFrameInterpolation.collectAsState()
  val animeEnabled by preferences.enableAnime4K.collectAsState()
  val animeMode by preferences.anime4kMode.collectAsState()
  val context = LocalContext.current
  val vulkanAvailable = remember(context) {
    BuildConfig.MPV_SUPPORTS_VULKAN && VulkanCapabilities.isDeviceSupported(context)
  }
  val configOwned = isAnyMpvOptionOwnedByConfig(MpvConfigControlledFeatures.FRAME_INTERPOLATION)
  // The running renderer, not a preferred engine that may have fallen back, is authoritative.
  return PlayerControlActionPolicy.interpolationAvailable(
    currentlyEnabled = enabled,
    vulkanAvailable = vulkanAvailable,
    nativeEngine = activity.isNativeEngineActive(),
    mpvRuntimeAvailable = BuildConfig.MPV_HAS_MPVFLOW,
    configOwned = configOwned,
    anime4kActive = animeEnabled && animeMode != "OFF",
  )
}

@Composable
internal fun FrameInterpolationPlayerButton(
  targetFpsAction: Boolean,
  activity: PlayerActivity,
  onOpenSheet: (Sheets) -> Unit,
  buttonSize: Dp,
  compact: Boolean,
  contentColor: Color,
) {
  val preferences = koinInject<DecoderPreferences>()
  val appearance = koinInject<AppearancePreferences>()
  val enabled by preferences.mpvFlowFrameInterpolation.collectAsState()
  val targetFps by preferences.mpvFlowTargetFps.collectAsState()
  val glass by appearance.liquidGlassSurfaces.collectAsState()
  val available = interpolationControlsAvailable(activity)
  val clickEvent = LocalPlayerButtonsClickEvent.current
  val onLabel = stringResource(R.string.player_interpolation_on)
  val offLabel = stringResource(R.string.player_interpolation_off)
  val nextPlaybackNote = interpolationApplyTimingNote(activity)
  val savedState = stringResource(R.string.interpolation_feedback_saved_state, if (enabled) onLabel else offLabel)
  val toggleTitle = if (activity.isNativeEngineActive()) stringResource(R.string.pref_decoder_mpvflow_title)
    else stringResource(R.string.interpolation_feedback_saved_title)
  if (!targetFpsAction) {
    ControlsButton(
      icon = Icons.RoundedFilled.AutoAwesome,
      onClick = {
        preferences.mpvFlowFrameInterpolation.set(!preferences.mpvFlowFrameInterpolation.get())
        activity.applyInterpolationPreferencesFromControls()
      },
      onLongClick = { onOpenSheet(Sheets.FrameInterpolationFps) },
      enabled = available,
      title = toggleTitle,
      color = if (enabled) MaterialTheme.colorScheme.primary else contentColor,
      modifier = Modifier.size(buttonSize).semantics {
        role = Role.Switch
        stateDescription = "$savedState. $nextPlaybackNote"
      },
    )
  } else if (compact) {
    ControlsButton(
      icon = Icons.RoundedFilled.Speed,
      onClick = { onOpenSheet(Sheets.FrameInterpolationFps) },
      enabled = available,
      title = stringResource(R.string.pref_decoder_mpvflow_target_fps_value, targetFps),
      color = contentColor,
      modifier = Modifier.size(buttonSize),
    )
  } else {
    val label = stringResource(R.string.pref_decoder_mpvflow_target_fps_value, targetFps)
    val hiddenBackground = LocalHidePlayerButtonsBackground.current
    Surface(
      modifier = Modifier.height(buttonSize).widthIn(min = buttonSize)
        .clip(CircleShape)
        .playerGlassPane(glass, cornerRadius = 999.dp)
        .semantics { contentDescription = label }
        .clickable(enabled = available, role = Role.Button) {
          clickEvent()
          onOpenSheet(Sheets.FrameInterpolationFps)
        },
      shape = CircleShape,
      color = if (glass || hiddenBackground) Color.Transparent else playerButtonContainerColor(),
      contentColor = if (available) contentColor else contentColor.copy(alpha = 0.38f),
      tonalElevation = 0.dp,
    ) {
      Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
      }
    }
  }
}
