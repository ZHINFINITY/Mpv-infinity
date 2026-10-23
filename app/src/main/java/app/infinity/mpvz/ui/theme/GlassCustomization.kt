/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Collected once by the app theme; controls do not each start four preference collectors. */
@Immutable
data class GlassCustomization(
  val frost: Float = 1f,
  val refraction: Float = 1f,
  val depth: Float = 1f,
  val motion: Float = 1f,
  val opacity: Float = 0.88f,
  val highlight: Float = 0.18f,
  val overrideMaterial: Boolean = false,
)
val LocalGlassCustomization = staticCompositionLocalOf { GlassCustomization() }
