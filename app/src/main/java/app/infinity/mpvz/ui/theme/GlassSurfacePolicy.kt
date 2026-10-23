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
  /** Light ceiling: a bright backdrop may darken a sheet/popup this far, never into a slab. */
  const val RegularMaximumFilmAlpha = 0.50f
  /** Browser popups only, when no scene can be frosted (power/thermal): the content is unblurred. */
  const val RegularNoSceneFilmAlpha = 0.85f
  /** Browser popup blur floor so library/settings text behind a popup is not readable. */
  const val BrowserPopupMinimumFrostDp = 22f
  /** Frost floor behind popup/menu text at frost strength 0; the frost setting adds on top. */
  const val BrowserPopupTextFrostFloorDp = 14f
  /** Extra popup frost per unit of the global frost setting (0..2). */
  const val BrowserPopupFrostPerStrengthDp = 6f
  /** Navigation-pill film when no scene is available (matches BrowserGlassSurface). */
  const val NavigationNoSceneFilmAlpha = 0.70f
  /**
   * Edge-to-edge top/selection bars without a sampled scene: page-background film
   * (pure black on AMOLED), with no white sheen band, rim line or shadow.
   */
  const val EdgeChromeNoSceneFilmAlpha = 0.88f

  /** Popups and menus carry readable text, so their frost never drops below the floor. */
  fun browserPopupFrostDp(frost: Float): Float =
    BrowserPopupTextFrostFloorDp + BrowserPopupFrostPerStrengthDp * GlassCustomizationPolicy.strength(frost)

  /** Navigation-pill frost: 2dp per strength unit with refraction, 3dp on the blur-only path. */
  fun navigationFrostDp(frost: Float, refraction: Boolean): Float =
    (if (refraction) 2f else 3f) * GlassCustomizationPolicy.strength(frost)

  /** A zero-radius pane is edge-to-edge chrome (top or selection bar), not a floating card. */
  fun isEdgeToEdgeChrome(cornerRadiusDp: Float): Boolean = cornerRadiusDp <= 0.5f
  /** Reference (0dd2a9cd) see-through sheet film: opacity * 0.42 over a sampled scene. */
  const val RegularFloorFilmFactor = 0.42f
  const val TextContrastTarget = 4.5f
  /** Browser scenes are display lists (no pixels): allow for thumbnails brighter than the page. */
  const val BrowserUnderlayMargin = 0.25f

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

  /** See-through floor for regular panes (0.30..0.42); the reference sheet film. */
  fun regularFloorFilmAlpha(opacity: Float): Float = opacity(opacity) * RegularFloorFilmFactor

  /**
   * Smallest film, starting at the see-through floor, that keeps [foreground] text at
   * [TextContrastTarget] over the frosted underlay, including the brightest top sheen and the
   * lower shade. Dark or ordinary video stays see-through; only a bright backdrop raises the film,
   * and never above [RegularMaximumFilmAlpha]. [underlaySrgb] is a bright-percentile sRGB luma.
   */
  fun regularAdaptiveFilmAlpha(
    opacity: Float,
    underlaySrgb: Float,
    tint: Color,
    foreground: Color,
    sheen: Float,
    shade: Float,
  ): Float {
    val floor = regularFloorFilmAlpha(opacity)
    val u = if (underlaySrgb.isFinite()) underlaySrgb.coerceIn(0f, 1f) else 1f
    val text = wcagLuminance(foreground)
    val s = if (sheen.isFinite()) sheen.coerceIn(0f, 1f) else 0f
    val d = if (shade.isFinite()) shade.coerceIn(0f, 1f) else 0f
    var a = floor
    while (a < RegularMaximumFilmAlpha) {
      val r = u * (1f - a) + tint.red * a
      val g = u * (1f - a) + tint.green * a
      val b = u * (1f - a) + tint.blue * a
      val ok = listOf(
        Triple(r, g, b),
        Triple(r + (1f - r) * s, g + (1f - g) * s, b + (1f - b) * s),
        Triple(r * (1f - d), g * (1f - d), b * (1f - d)),
      ).all { (cr, cg, cb) ->
        contrastRatio(text, wcagLuminance(Color(cr.coerceIn(0f, 1f), cg.coerceIn(0f, 1f), cb.coerceIn(0f, 1f)))) >=
          TextContrastTarget
      }
      if (ok) return a
      a += 0.02f
    }
    return RegularMaximumFilmAlpha
  }

  /** Browser scenes cannot be read back; estimate the frosted underlay from the page colour. */
  fun browserUnderlayEstimate(background: Color): Float =
    (srgbLuma(background) + BrowserUnderlayMargin).coerceIn(0f, 1f)

  /**
   * Bright-percentile sRGB luma of a frosted ARGB sample (sparse, bounded). Using a high
   * percentile rather than the mean keeps text readable over local highlights.
   */
  fun underlayLuma(pixels: IntArray, step: Int = 4, percentile: Float = 0.85f): Float {
    if (pixels.isEmpty()) return 1f
    val bins = IntArray(64)
    var count = 0
    var i = 0
    val stride = step.coerceAtLeast(1)
    while (i < pixels.size) {
      val p = pixels[i]
      val luma = (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
      bins[(luma * 63f).toInt().coerceIn(0, 63)]++
      count++
      i += stride
    }
    val target = (count * percentile.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
    var seen = 0
    for (bin in bins.indices) {
      seen += bins[bin]
      if (seen >= target) return (bin + 1) / 64f
    }
    return 1f
  }

  /** WCAG relative luminance of an sRGB colour. */
  fun wcagLuminance(color: Color): Float {
    fun linear(c: Float): Float = if (c <= 0.04045f) c / 12.92f
      else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
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
