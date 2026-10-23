/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import kotlin.math.PI
import kotlin.math.sin

/** Pure geometry for one selected lens. No clocks, platform state or continuing frame loop. */
object NavigationGlassMotionPolicy {
  const val MaxStretch = 0.18f
  const val MaxDeformation = 1f
  data class Geometry(val left: Float, val width: Float, val deformation: Float)

  fun dragGeometry(left: Float, width: Float, progress: Float, direction: Int, intensity: Float = 1f): Geometry {
    val x = left.takeIf { it.isFinite() } ?: 0f
    val w = width.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val p = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val sign = direction.coerceIn(-1, 1)
    val amount = intensity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val pulse = if (sign == 0 || p == 0f || p == 1f) 0f else sin(p.toDouble() * PI).toFloat().coerceIn(0f, 1f) * amount
    val extra = w * MaxStretch * pulse
    val stretchedWidth = (w.toDouble() + extra).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
    // The moving head stays aligned while a short tail stretches behind it.
    val trailingLeft = (x.toDouble() - if (sign > 0) extra.toDouble() else 0.0)
      .coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
    val deformation = if (pulse == 0f) 0f else (sign * pulse).coerceIn(-MaxDeformation, MaxDeformation)
    return Geometry(trailingLeft, stretchedWidth, deformation)
  }

  fun settledGeometry(left: Float, width: Float): Geometry = Geometry(
    left.takeIf { it.isFinite() } ?: 0f,
    width.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f,
    0f,
  )

  fun clampDirection(direction: Float): Int = when {
    !direction.isFinite() -> 0
    direction > 0f -> 1
    direction < 0f -> -1
    else -> 0
  }
}
