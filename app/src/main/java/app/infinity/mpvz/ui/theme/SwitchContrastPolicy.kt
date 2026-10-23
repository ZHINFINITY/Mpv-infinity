/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import androidx.compose.ui.graphics.Color

/** State colours chosen from the rendered surface, not the system dark-mode flag. */
data class SwitchContrastColors(
  val checkedTrack: Color,
  val checkedThumb: Color,
  val uncheckedTrack: Color,
  val uncheckedThumb: Color,
  val checkedBorder: Color,
  val uncheckedBorder: Color,
  val icon: Color,
)

object SwitchContrastPolicy {
  private const val DARK_SURFACE_LUMINANCE = 0.35f
  private val Light = Color(0xFFF7F7F7)
  private val Dark = Color(0xFF111111)
  private val DimLight = Color(0x33FFFFFF)
  private val DimDark = Color(0x33111111)
  fun forSurfaceLuminance(surfaceLuminance: Float): SwitchContrastColors {
    val dark = surfaceLuminance.coerceIn(0f, 1f) < DARK_SURFACE_LUMINANCE
    return if (dark) SwitchContrastColors(Light, Dark, DimLight, Light, Light.copy(alpha = .72f), Light.copy(alpha = .72f), Light)
    else SwitchContrastColors(Dark, Light, DimDark, Dark, Dark.copy(alpha = .72f), Dark.copy(alpha = .72f), Dark)
  }
}
