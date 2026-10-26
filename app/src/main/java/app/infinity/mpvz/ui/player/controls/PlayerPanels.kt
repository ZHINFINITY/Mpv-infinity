/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import app.infinity.mpvz.preferences.preference.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.MpvConfigControlledFeatures
import app.infinity.mpvz.ui.player.Panels
import app.infinity.mpvz.ui.player.PlayerActivity
import app.infinity.mpvz.ui.player.PlayerViewModel
import app.infinity.mpvz.ui.player.clip.ClipOverlayView
import app.infinity.mpvz.ui.player.controls.components.MpvConfigOwnedPanel
import app.infinity.mpvz.ui.player.controls.components.panels.AudioDelayPanel
import app.infinity.mpvz.ui.player.controls.components.panels.HdrScreenOutputPanel
import app.infinity.mpvz.ui.player.controls.components.panels.LuaScriptsPanel
import app.infinity.mpvz.ui.player.controls.components.panels.SubtitleDelayPanel
import app.infinity.mpvz.ui.player.controls.components.panels.SubtitleSettingsPanel
import app.infinity.mpvz.ui.player.controls.components.panels.VideoSettingsPanel
import app.infinity.mpvz.ui.utils.isAnyMpvOptionOwnedByConfig
import app.infinity.mpvz.ui.utils.isMpvOptionOwnedByConfig

@Composable
fun PlayerPanels(
  panelShown: Panels,
  viewModel: PlayerViewModel,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedContent(
    targetState = panelShown,
    label = "panels",
    contentAlignment = Alignment.CenterEnd,
    contentKey = { it.name },
    transitionSpec = {
      // clip = false: the edge-attached Subtitles panel's glass reaches past its slot to the
      // display edges and must not be cut at the content bounds.
      (fadeIn() + slideInHorizontally { it / 3 } togetherWith fadeOut() + slideOutHorizontally { it / 2 })
        .using(SizeTransform(clip = false))
    },
    modifier = modifier,
  ) { currentPanel ->
    val configOwned =
      when (currentPanel) {
        Panels.AudioDelay -> isMpvOptionOwnedByConfig("audio-delay")
        Panels.HdrScreenOutput -> isAnyMpvOptionOwnedByConfig(MpvConfigControlledFeatures.HDR_OUTPUT)
        else -> false
      }
    if (configOwned) {
      MpvConfigOwnedPanel(onDismissRequest)
      return@AnimatedContent
    }
    when (currentPanel) {
      Panels.None -> {
        Box(Modifier.fillMaxHeight())
      }
      Panels.Clip -> {
        val activity = LocalActivity.current as? PlayerActivity
        if (activity != null) {
          val clipOverlay = remember(activity) { ClipOverlayView.ensureAttached(activity) }
          clipOverlay.EditorPanel(onDismissRequest)
        }
      }
      Panels.SubtitleSettings -> {
        SubtitleSettingsPanel(
          viewModel = viewModel,
          onDismissRequest = onDismissRequest,
        )
      }
      Panels.SubtitleDelay -> {
        SubtitleDelayPanel(onDismissRequest)
      }
      Panels.AudioDelay -> {
        AudioDelayPanel(onDismissRequest)
      }
      Panels.VideoFilters -> {
        VideoSettingsPanel(onDismissRequest)
      }
      Panels.LuaScripts -> {
        LuaScriptsPanel(onDismissRequest)
      }
      Panels.HdrScreenOutput -> {
        HdrScreenOutputPanel(
          viewModel = viewModel,
          onDismissRequest = onDismissRequest,
        )
      }
    }
  }
}

val CARDS_MAX_WIDTH = 420.dp
val panelCardsColors: @Composable () -> CardColors = {
  val appearance = org.koin.compose.koinInject<app.infinity.mpvz.preferences.AppearancePreferences>()
  val glassPreference by appearance.liquidGlassSurfaces.collectAsState()
  val context = androidx.compose.ui.platform.LocalContext.current
  val deviceAllowsGlass = remember(context) { app.infinity.mpvz.ui.theme.GlassDeviceSupport.allowsGlass(context) }
  val glass = glassPreference && deviceAllowsGlass
  val scheme = MaterialTheme.colorScheme
  CardDefaults.cardColors(
    containerColor = PanelCardFrostPolicy.container(scheme.surfaceContainer, scheme.onSurface, glass),
    contentColor = scheme.onSurface,
    disabledContainerColor = PanelCardFrostPolicy.container(scheme.surfaceContainerHighest, scheme.onSurface, glass),
    disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
  )
}

/**
 * Inner cards inside glass sheets (e.g. the subtitle Typography card) are a lighter second
 * frost layer over the already-frosted sheet instead of a near-opaque block. Without glass
 * (setting off or unsupported device) they keep the classic readable 0.85 surface.
 */
object PanelCardFrostPolicy {
  const val ClassicAlpha = 0.85f
  /** Second frost layer: translucent so the sheet's frost shows through. */
  const val GlassAlpha = 0.36f
  /** Slight lift toward the content colour so the card still separates from the sheet. */
  const val GlassLift = 0.07f

  fun container(base: Color, onSurface: Color, glass: Boolean): Color =
    if (!glass) base.copy(alpha = ClassicAlpha)
    else androidx.compose.ui.graphics.lerp(base.copy(alpha = 1f), onSurface.copy(alpha = 1f), GlassLift)
      .copy(alpha = GlassAlpha)
}
