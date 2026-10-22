/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.theme

import kotlin.math.abs
import androidx.compose.ui.graphics.Color

/** Bounded optical geometry shared by the material renderer and its tests. */
object GlassSurfacePolicy {
  const val MinOpacity = 0.72f
  const val MaxOpacity = 1f
  const val MinHighlight = 0f
  const val MaxHighlight = 0.36f
  const val RegularMinimumFrostDp = 12f
  const val RegularMinimumFilmAlpha = 0.88f
  const val RegularMaximumFilmAlpha = 0.92f

  /** Clear keeps compact controls visually connected to media; regular protects text-heavy surfaces. */
  enum class MaterialRole { Clear, Regular }

  data class MaterialTuning(
    val frostMultiplier: Float,
    val tintAlpha: Float,
    val refractionMultiplier: Float,
  )

  fun materialTuning(role: MaterialRole): MaterialTuning = when (role) {
    MaterialRole.Clear -> MaterialTuning(frostMultiplier = 1f, tintAlpha = 0.32f, refractionMultiplier = 1f)
    // A popup must have an evenly frosted, quiet center: the rim may refract, but text behind it cannot compete.
    MaterialRole.Regular -> MaterialTuning(frostMultiplier = 1.35f, tintAlpha = 0.86f, refractionMultiplier = 0.42f)
  }

  /** Minimum regular center film; translucent and bounded, never an opaque popup rectangle. */
  fun regularCenterFilmAlpha(opacity: Float): Float {
    val fraction = (opacity(opacity) - MinOpacity) / (MaxOpacity - MinOpacity)
    return RegularMinimumFilmAlpha + (RegularMaximumFilmAlpha - RegularMinimumFilmAlpha) * fraction
  }

  /**
   * Pick a theme-directed, sRGB tint endpoint for regular panes.  A themed
   * surface color alone cannot guarantee contrast over both black and white
   * underlays; onSurface establishes whether text is light or dark, while the
   * endpoint keeps the center film translucent rather than adding a rectangle.
   */
  fun regularTintColor(tint: Color, onSurface: Color): Color {
    val foreground = srgbLuma(onSurface)
    val base = if (foreground >= 0.5f) Color.Black else Color.White
    // Retain only a restrained amount of theme tint: at the minimum film alpha
    // a brighter dark tint would fail white text over a white underlay.
    return Color(
      red = base.red * 0.98f + tint.red * 0.02f,
      green = base.green * 0.98f + tint.green * 0.02f,
      blue = base.blue * 0.98f + tint.blue * 0.02f,
      alpha = 1f,
    )
  }

  /** sRGB-channel luminance; do not use linear-light interpolation for UI compositing. */
  fun srgbLuma(color: Color): Float =
    (0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue).coerceIn(0f, 1f)

  /** Composite luminance used by tests for known tint and light/dark underlay targets. */
  fun compositeLuminance(underlay: Float, tint: Float, tintAlpha: Float): Float {
    val alpha = if (tintAlpha.isFinite()) tintAlpha.coerceIn(0f, 1f) else 0f
    val base = if (underlay.isFinite()) underlay.coerceIn(0f, 1f) else 0.5f
    val overlay = if (tint.isFinite()) tint.coerceIn(0f, 1f) else 0.5f
    return base * (1f - alpha) + overlay * alpha
  }

  data class PaneGeometry(val width: Float, val height: Float, val radius: Float) {
    val safeRadius: Float get() = radius.coerceIn(0f, minOf(width, height) / 2f)
    /** Same rounded rectangle used by the cached draw-path, not its rectangular bounding box. */
    fun contains(x: Float, y: Float): Boolean {
      if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || x > width || y > height) return false
      val r = safeRadius
      val dx = (abs(x - width / 2f) - (width / 2f - r)).coerceAtLeast(0f)
      val dy = (abs(y - height / 2f) - (height / 2f - r)).coerceAtLeast(0f)
      return dx * dx + dy * dy <= r * r
    }
  }

  data class EdgeLight(val x: Float, val y: Float)
  val TopLeftLight = EdgeLight(-0.72f, -0.68f)

  data class DepthOptics(
    val bodyHighlight: Float,
    val bodyShade: Float,
    val rimHighlight: Float,
    val rimShade: Float,
    val rimWidthDp: Float,
    val shadowAlpha: Float,
    val shadowSpreadDp: Float,
  )

  fun geometry(width: Float, height: Float, radius: Float): PaneGeometry =
    PaneGeometry(nonnegative(width), nonnegative(height), nonnegative(radius))

  /** Static thickness stays visible with reduced motion; only moving/lensing effects stop. */
  fun depthOptics(depth: Float, highlight: Float, highContrast: Boolean = false): DepthOptics {
    val d = strength(depth)
    val h = GlassSurfacePolicy.highlight(highlight)
    return DepthOptics(
      bodyHighlight = if (highContrast) 0f else 0.15f * d,
      bodyShade = if (highContrast) 0f else 0.10f * d,
      rimHighlight = if (highContrast) 0f else (0.16f + h * 0.5f + d * 0.06f),
      rimShade = if (highContrast) 0f else 0.12f + 0.10f * d,
      rimWidthDp = if (highContrast) 1.5f else 0.8f + 0.3f * d,
      shadowAlpha = if (highContrast) 0f else 0.06f * d,
      shadowSpreadDp = if (d == 0f || highContrast) 0f else 1f + d * 2f,
    )
  }

  fun refractionStrength(value: Float, reducedMotion: Boolean, highContrast: Boolean): Float =
    if (reducedMotion || highContrast) 0f else strength(value)

  fun filmAlpha(value: Float, highContrast: Boolean): Float =
    if (highContrast) 1f else if (value.isFinite()) value.coerceIn(0f, 1f) else 0.88f

  /** Sampling the theme is supplementary; the actual content underneath remains visible. */
  fun browserSampleAlpha(value: Float): Float = opacity(value) * 0.40f

  /** No scene (power/thermal fallback or a separate window) must not turn into a solid card. */
  fun browserFallbackAlpha(value: Float): Float = opacity(value) * 0.28f

  /** WCAG relative-luminance ratio, kept pure so JVM tests can validate both theme targets. */
  fun contrastRatio(foregroundLuminance: Float, backgroundLuminance: Float): Float {
    val foreground = if (foregroundLuminance.isFinite()) foregroundLuminance.coerceIn(0f, 1f) else 0.5f
    val background = if (backgroundLuminance.isFinite()) backgroundLuminance.coerceIn(0f, 1f) else 0.5f
    return (maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f)
  }

  fun opacity(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinOpacity, MaxOpacity) else 0.88f

  fun highlight(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinHighlight, MaxHighlight) else 0.18f

  private fun strength(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 2f) else 1f
  private fun nonnegative(value: Float): Float = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
}
