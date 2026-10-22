/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.preferences.components

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.liquidGlassPane
import app.infinity.mpvz.ui.theme.LocalGlassCustomization
import org.koin.compose.koinInject

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
  val appearance = koinInject<AppearancePreferences>()
  val enabled by appearance.liquidGlassSurfaces.collectAsState()
  if (!enabled) return this
  val tuning = LocalGlassCustomization.current
  return liquidGlassPane(
    enabled = enabled,
    opacity = opacity ?: tuning.opacity,
    highlight = highlight ?: tuning.highlight,
    cornerRadius = cornerRadius,
  )
}

/** Every setting receives the same card bounds; the switch only changes its material. */
@Composable
fun Modifier.settingsGlassPreferenceRow(): Modifier =
  run {
    val enabled = settingsGlassEnabled()
    val geometry = SettingsGlassPolicy.geometry(enabled)
    val shape = RoundedCornerShape(geometry.cornerRadiusDp.dp)
    val row = padding(
      horizontal = geometry.horizontalInsetDp.dp,
      vertical = geometry.verticalSpacingDp.dp,
    ).heightIn(min = geometry.minimumHeightDp.dp).clip(shape)
    if (enabled) row.settingsGlassSurface(cornerRadius = geometry.cornerRadiusDp.dp)
    else row.background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
  }

/** Keep the caller's exact opaque Material fill when glass is disabled. */
@Composable
fun settingsGlassContainerColor(fallback: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
  val appearance = koinInject<AppearancePreferences>()
  val enabled by appearance.liquidGlassSurfaces.collectAsState()
  return if (enabled) androidx.compose.ui.graphics.Color.Transparent else fallback
}
