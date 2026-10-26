/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Lit edge ("rim light") shared by every glass surface and control.
 *
 * The light sits at the top-left: the top and left edges carry a bright specular band that
 * fades along the edge (softness sets how far), the bottom-right edge gets a faint shade so the
 * surface reads as a thick, raised slab. It is pure gradient strokes clipped to the surface's
 * own outline: no blur, no offscreen layer, no sampling, and cached per size/shape/tuning.
 */
object GlassRimLightPolicy {
  const val DefaultStrength = 1f
  const val MaxStrength = 2f
  const val DefaultWidthDp = 1.2f
  const val MinWidthDp = 0.5f
  const val MaxWidthDp = 3f
  const val DefaultSoftness = 0.5f

  fun strength(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, MaxStrength) else DefaultStrength

  fun widthDp(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinWidthDp, MaxWidthDp) else DefaultWidthDp

  fun softness(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else DefaultSoftness

  @Immutable
  data class Rim(
    /** Peak alpha of the top-left specular band. */
    val highlightAlpha: Float,
    /** Gradient position (0 = top-left corner, 1 = bottom-right) where the band has faded. */
    val highlightStop: Float,
    /** Alpha of the bottom-right inner shade. */
    val shadeAlpha: Float,
    /** Alpha of the wide, soft halo drawn inside the edge. */
    val glowAlpha: Float,
    /** Halo width as a multiple of the rim width. */
    val glowWidthScale: Float,
  ) {
    val visible: Boolean get() = highlightAlpha > 0.002f || shadeAlpha > 0.002f
  }

  /**
   * @param boost 0..1 extra light while a control is pressed/dragged.
   * @param highContrast keeps the shade line strong enough to outline the surface.
   */
  fun rim(
    strength: Float,
    softness: Float,
    darkTheme: Boolean,
    boost: Float = 0f,
    highContrast: Boolean = false,
  ): Rim {
    val s = strength(strength)
    val soft = softness(softness)
    val b = if (boost.isFinite()) boost.coerceIn(0f, 1f) else 0f
    // A white band reads strongly on dark glass; light glass needs a slightly stronger band
    // and a lighter shade so it doesn't look like a drop outline.
    val baseHighlight = if (darkTheme) 0.38f else 0.55f
    val baseShade = if (darkTheme) 0.20f else 0.12f
    val highlight = (baseHighlight * s * (1f + 0.6f * b)).coerceIn(0f, 0.9f)
    var shade = (baseShade * s).coerceIn(0f, 0.45f)
    if (highContrast) shade = shade.coerceAtLeast(0.32f)
    return Rim(
      highlightAlpha = highlight,
      highlightStop = 0.14f + 0.36f * soft,
      shadeAlpha = shade,
      glowAlpha = highlight * 0.32f * soft,
      glowWidthScale = 1f + 2.5f * soft,
    )
  }

  /** Mix the light colour slightly toward an ambient (e.g. video dominant) colour. */
  fun lightColor(ambient: Color?, amount: Float = 0.25f): Color {
    if (ambient == null || ambient == Color.Unspecified) return Color.White
    return lerp(Color.White, ambient.copy(alpha = 1f), amount.coerceIn(0f, 0.5f))
  }
}

/**
 * Draws the shared lit edge behind this node's content (content stays sharp on top).
 *
 * @param shape the surface's own shape; edge-attached sheets pass a shape rounded only on
 *   their free edge and the rim follows it.
 * @param ambient optional colour the light is tinted toward (video dominant colour). It is
 *   read only inside the draw pass, so a smoothly changing ambient colour redraws the rim
 *   without recomposing the surface; brushes are rebuilt only when the colour changes.
 * @param boost read only inside the draw pass, so animating it never recomposes.
 */
@Composable
fun Modifier.glassRimLight(
  shape: Shape,
  enabled: Boolean = true,
  ambient: (() -> Color?)? = null,
  boost: () -> Float = { 0f },
  highContrast: Boolean = false,
): Modifier {
  if (!enabled) return this
  val tuning = LocalGlassCustomization.current
  if (tuning.rimStrength <= 0f) return this
  val darkTheme = GlassSurfacePolicy.srgbLuma(MaterialTheme.colorScheme.surface) < 0.5f
  val widthDp = tuning.rimWidthDp
  return drawWithCache {
    val outline = if (shape === RectangleShape) null else shape.createOutline(size, layoutDirection, this)
    val path = Path().apply {
      when (outline) {
        null -> addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        is Outline.Rectangle -> addRect(outline.rect)
        is Outline.Rounded -> addRoundRect(outline.roundRect)
        is Outline.Generic -> addPath(outline.path)
      }
    }
    val width = widthDp.dp.toPx()
    fun brushFor(rim: GlassRimLightPolicy.Rim, light: Color): Brush = Brush.linearGradient(
      0f to light.copy(alpha = rim.highlightAlpha),
      rim.highlightStop to light.copy(alpha = rim.highlightAlpha * 0.42f),
      0.5f to light.copy(alpha = rim.highlightAlpha * 0.08f),
      0.78f to Color.Transparent,
      1f to Color.Black.copy(alpha = rim.shadeAlpha),
      start = Offset.Zero,
      end = Offset(size.width, size.height),
    )
    fun glowFor(rim: GlassRimLightPolicy.Rim, light: Color): Brush = Brush.linearGradient(
      0f to light.copy(alpha = rim.glowAlpha),
      rim.highlightStop to light.copy(alpha = rim.glowAlpha * 0.3f),
      0.5f to Color.Transparent,
      start = Offset.Zero,
      end = Offset(size.width, size.height),
    )
    val restRim = GlassRimLightPolicy.rim(tuning.rimStrength, tuning.rimSoftness, darkTheme,
      highContrast = highContrast)
    var cachedLight = Color.White
    var restBrush = brushFor(restRim, cachedLight)
    var restGlow = glowFor(restRim, cachedLight)
    onDrawBehind {
      val light = GlassRimLightPolicy.lightColor(ambient?.invoke())
      if (light != cachedLight) {
        cachedLight = light
        restBrush = brushFor(restRim, light)
        restGlow = glowFor(restRim, light)
      }
      val b = boost()
      val rim: GlassRimLightPolicy.Rim
      val edge: Brush
      val halo: Brush
      if (b > 0.001f) {
        rim = GlassRimLightPolicy.rim(tuning.rimStrength, tuning.rimSoftness, darkTheme, b, highContrast)
        edge = brushFor(rim, light)
        halo = glowFor(rim, light)
      } else {
        rim = restRim; edge = restBrush; halo = restGlow
      }
      if (!rim.visible) return@onDrawBehind
      // Strokes are centred on the outline; clipping to it keeps only the inner half, so the
      // rim never spills outside the surface or over neighbours.
      clipPath(path) {
        if (rim.glowAlpha > 0.002f) drawPath(path, halo, style = Stroke(width * rim.glowWidthScale * 2f))
        drawPath(path, edge, style = Stroke(width * 2f))
      }
    }
  }
}

/** Static-colour convenience (controls tinted by a fixed accent). */
@Composable
fun Modifier.glassRimLight(
  shape: Shape,
  ambient: Color,
  enabled: Boolean = true,
  boost: () -> Float = { 0f },
  highContrast: Boolean = false,
): Modifier = glassRimLight(shape, enabled, { ambient }, boost, highContrast)
