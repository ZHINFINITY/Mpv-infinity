/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.preferences.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.liquidGlassPane
import org.koin.compose.koinInject

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
): Modifier {
  val appearance = koinInject<AppearancePreferences>()
  val enabled by appearance.liquidGlassSurfaces.collectAsState()
  val storedOpacity by appearance.glassSurfaceOpacity.collectAsState()
  val storedHighlight by appearance.glassSurfaceHighlight.collectAsState()
  return liquidGlassPane(
    enabled = enabled,
    opacity = opacity ?: storedOpacity,
    highlight = highlight ?: storedHighlight,
    cornerRadius = cornerRadius,
  )
}

/** Keep the caller's exact opaque Material fill when glass is disabled. */
@Composable
fun settingsGlassContainerColor(fallback: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
  val appearance = koinInject<AppearancePreferences>()
  val enabled by appearance.liquidGlassSurfaces.collectAsState()
  return if (enabled) androidx.compose.ui.graphics.Color.Transparent else fallback
}
