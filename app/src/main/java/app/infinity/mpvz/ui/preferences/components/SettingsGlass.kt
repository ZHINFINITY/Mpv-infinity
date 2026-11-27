/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.preferences.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.liquidGlassPane
import app.infinity.mpvz.ui.theme.paneIsInPopupWindow
import app.infinity.mpvz.ui.theme.LocalGlassCustomization
import app.infinity.mpvz.ui.theme.GlassSurfacePolicy
import org.koin.compose.koinInject

/** One grouped preference card owns the shared glass pane; child rows keep native geometry. */
val LocalSettingsGlassOwner = compositionLocalOf { false }

/** Shared master state for native fill/elevation fallbacks around a glass-owned surface. */
@Composable
fun settingsGlassEnabled(): Boolean {
  val appearance = koinInject<AppearancePreferences>()
  val enabled by appearance.liquidGlassSurfaces.collectAsState()
  return enabled
}

/**
 * Shared background-only optics for settings rows, cards and sheets.
 *
 * The master switch is intentionally read here so every settings surface has the same
 * fallback: disabled means the original Material surface/semantics, with no glass overlay.
 * It never owns padding or input bounds, so callers retain click, focus, RTL and semantics.
 */
@Composable
fun Modifier.settingsGlassSurface(
  cornerRadius: Dp = 20.dp,
  opacity: Float? = null,
  highlight: Float? = null,
  independent: Boolean = false,
): Modifier {
  if (LocalSettingsGlassOwner.current && !independent) return this
  val appearance = koinInject<AppearancePreferences>()
  val enabled by appearance.liquidGlassSurfaces.collectAsState()
  if (!enabled) return this
  val tuning = LocalGlassCustomization.current
  // Independent geometry never bypasses the master or global material tuning.
  // liquidGlassPane owns the override policy for both browser and player scenes.
  // A pane composed into a dialog/popup window presents itself as a popup pane: Clear panes
  // never sample the popup scene by policy, so a dialog that stayed Clear had nothing to frost
  // and fell back to a flat fill — that is why liquid glass was missing from popups.
  val inPopupWindow = paneIsInPopupWindow()
  return liquidGlassPane(
    enabled = enabled,
    opacity = opacity ?: tuning.opacity,
    highlight = highlight ?: tuning.highlight,
    cornerRadius = cornerRadius,
    materialRole = if (independent || inPopupWindow) GlassSurfacePolicy.MaterialRole.Regular
      else GlassSurfacePolicy.MaterialRole.Clear,
  )
}

/** Keep the caller's exact opaque Material fill when glass is disabled. */
@Composable
fun settingsGlassContainerColor(fallback: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
  val appearance = koinInject<AppearancePreferences>()
  val enabled by appearance.liquidGlassSurfaces.collectAsState()
  return if (enabled) {
    // Glass optics sample the backdrop behind the pane and paint their own adaptive film, which
    // already guarantees text contrast (see GlassSurfacePolicy.regularAdaptiveFilmAlpha). Only a
    // light scrim is added on top: a heavier one (0.42) double-filmed every popup into a grey slab.
    fallback.copy(alpha = fallback.alpha.coerceAtLeast(GlassSurfacePolicy.GlassContainerScrimAlpha))
  } else {
    fallback
  }
}
