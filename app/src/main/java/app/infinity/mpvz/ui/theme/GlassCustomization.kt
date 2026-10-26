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
  /** Lit edge strength, 0..2 (1 = default look). */
  val rimStrength: Float = GlassRimLightPolicy.DefaultStrength,
  /** Lit edge stroke width in dp, 0.5..3. */
  val rimWidthDp: Float = GlassRimLightPolicy.DefaultWidthDp,
  /** How far the top-left highlight spreads along the edge before fading, 0..1. */
  val rimSoftness: Float = GlassRimLightPolicy.DefaultSoftness,
  /** Lens refraction (how far the glass edge bends what is behind it), 0..2 (1 = default). */
  val lensRefraction: Float = 1f,
  /** Lens depth (magnification / thickness of the bend band), 0..2 (1 = default). */
  val lensDepth: Float = 1f,
  /** Thin rainbow (chromatic) edge on lens surfaces. */
  val colourEdge: Boolean = true,
)
val LocalGlassCustomization = staticCompositionLocalOf { GlassCustomization() }
