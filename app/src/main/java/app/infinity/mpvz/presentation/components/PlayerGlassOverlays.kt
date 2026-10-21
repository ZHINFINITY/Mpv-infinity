/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.liquidGlassPane
import org.koin.compose.koinInject

/** Apply to the entire popup surface, never just its title, text, or menu items.
 * Separate windows share screen-space sampling. Without a sample, the optical renderer
 * supplies a readable translucent film; glass-off retains an opaque Material surface.
 */
@Composable
fun Modifier.playerGlassOverlay(cornerRadius: Dp = 28.dp): Modifier {
  val appearance = koinInject<AppearancePreferences>()
  val enabled by appearance.liquidGlassSurfaces.collectAsState()
  val opacity by appearance.glassSurfaceOpacity.collectAsState()
  val highlight by appearance.glassSurfaceHighlight.collectAsState()
  val shape = RoundedCornerShape(cornerRadius)
  return clip(shape)
    .liquidGlassPane(enabled, opacity, highlight, cornerRadius)
    .then(if (enabled) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape))
}

@Composable
fun PlayerGlassOverlay(
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 28.dp,
  content: @Composable () -> Unit,
) {
  Box(modifier.playerGlassOverlay(cornerRadius)) { content() }
}
