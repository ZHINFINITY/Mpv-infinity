/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.theme

import kotlin.math.abs

/** Bounded optical geometry shared by the material renderer and its tests. */
object GlassSurfacePolicy {
  const val MinOpacity = 0.72f
  const val MaxOpacity = 1f
  const val MinHighlight = 0f
  const val MaxHighlight = 0.36f

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

  fun opacity(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinOpacity, MaxOpacity) else 0.88f

  fun highlight(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MinHighlight, MaxHighlight) else 0.18f

  private fun strength(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 2f) else 1f
  private fun nonnegative(value: Float): Float = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
}
