/*
 * Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms.
 * Original native rounded-bevel geometry; no third-party shader or asset is used.
 */
package app.infinity.mpvz.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Renderer inputs for one geometry-matched rounded pane. Values are finite and bounded so
 * malformed preferences or sizes cannot turn the RuntimeShader into an unbounded lens.
 */
object GlassBevelPolicy {
  data class Params(
    val radius: Float,
    val bevel: Float,
    val normalX: Float,
    val normalY: Float,
    val specular: Float,
    val fresnel: Float,
    val refractionX: Float,
    val refractionY: Float,
  )

  fun params(
    width: Float,
    height: Float,
    radius: Float,
    depth: Float,
    refraction: Float,
    reducedMotion: Boolean,
    highContrast: Boolean,
  ): Params {
    val w = finite(width).coerceAtLeast(0f)
    val h = finite(height).coerceAtLeast(0f)
    val r = finite(radius).coerceIn(0f, min(w, h) / 2f)
    val d = finite(depth).coerceIn(0f, 2f)
    val lens = if (reducedMotion || highContrast) 0f else finite(refraction).coerceIn(0f, 2f)
    val lightLength = sqrt(0.72f * 0.72f + 0.68f * 0.68f).coerceAtLeast(0.001f)
    val lx = -0.72f / lightLength
    val ly = -0.68f / lightLength
    return Params(
      radius = r,
      bevel = if (highContrast) 0f else (0.65f + d * 1.35f).coerceAtMost(max(0.65f, r)),
      normalX = lx,
      normalY = ly,
      specular = if (highContrast) 0f else (0.16f + d * 0.24f).coerceIn(0f, 0.72f),
      fresnel = if (highContrast || reducedMotion) 0f else (0.08f + d * 0.14f).coerceIn(0f, 0.42f),
      refractionX = lx * lens * 3.5f,
      refractionY = ly * lens * 3.5f,
    )
  }

  /** Analytic outward normal for the same rounded rectangle used by the renderer. */
  fun edgeNormal(width: Float, height: Float, radius: Float, x: Float, y: Float): Pair<Float, Float> {
    val w = finite(width).coerceAtLeast(0f)
    val h = finite(height).coerceAtLeast(0f)
    val r = finite(radius).coerceIn(0f, min(w, h) / 2f)
    val cx = (w / 2f)
    val cy = (h / 2f)
    val qx = abs(finite(x) - cx) - (cx - r)
    val qy = abs(finite(y) - cy) - (cy - r)
    val nx: Float
    val ny: Float
    if (qx > 0f && qy > 0f) {
      val length = sqrt(qx * qx + qy * qy).coerceAtLeast(0.0001f)
      nx = qx / length * if (x >= cx) 1f else -1f
      ny = qy / length * if (y >= cy) 1f else -1f
    } else if (qx > qy) {
      nx = if (x >= cx) 1f else -1f; ny = 0f
    } else {
      nx = 0f; ny = if (y >= cy) 1f else -1f
    }
    return nx to ny
  }

  private fun finite(value: Float): Float = if (value.isFinite()) value else 0f
}
