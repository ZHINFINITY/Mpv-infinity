/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.infinity.mpvz.preferences.AppearancePreferences
import app.infinity.mpvz.preferences.preference.collectAsState
import app.infinity.mpvz.ui.theme.AppMotion
import app.infinity.mpvz.ui.theme.liquidGlassPane
import org.koin.compose.koinInject

/** Surface treatment is independent of whether the renderer exposes a sampleable video layer. */
internal val LocalPlayerGlassSurfaceActive = staticCompositionLocalOf { false }

/** Keep the panel material enabled when native HDR prevents live video sampling. */
@Composable
internal fun playerGlassSurfaceEnabled(): Boolean {
  val enabled by koinInject<AppearancePreferences>().liquidGlassSurfaces.collectAsState()
  return enabled
}

@Composable
internal fun playerGlassContainerColor(
  color: Color,
  selected: Boolean = false,
  frosted: Boolean = LocalPlayerGlassSurfaceActive.current,
): Color = if (frosted) color.copy(alpha = color.alpha * (if (selected) 0.65f else 0.35f)) else color

/**
 * Shared material for player sheets and movable panels. Delegates to the app's
 * [app.infinity.mpvz.ui.theme.liquidGlassPane] optics so player panels use the
 * same runtime shader, customization and reduced-motion handling as every other
 * glass surface; only the frosted backing tint differs.
 */
@Composable
internal fun Modifier.playerGlassSurface(
  backdrop: Any?,
  shape: Shape,
  containerColor: Color,
  enabled: Boolean = true,
): Modifier {
  if (!enabled) return this
  val reducedMotion = AppMotion.shouldReduceMotion()
  // Preserve the player's original 80% frosted backing. Rows inside it provide lighter
  // 35% / 65% resting / selected surfaces, rather than opaque cards hiding the video.
  val tint =
    Brush.verticalGradient(
      0f to containerColor.copy(alpha = containerColor.alpha.coerceAtMost(0.86f)),
      0.25f to containerColor.copy(alpha = containerColor.alpha.coerceAtMost(0.80f)),
      1f to containerColor.copy(alpha = containerColor.alpha.coerceAtMost(0.80f)),
    )
  return liquidGlassPane(
    enabled = true,
    opacity = 0.80f,
    highlight = if (reducedMotion) 0.10f else 0.20f,
  )
    .background(tint, shape)
}
