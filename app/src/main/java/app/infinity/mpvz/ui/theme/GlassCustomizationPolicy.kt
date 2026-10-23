/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import kotlin.math.roundToInt

/** Invalid imported preferences cannot produce NaN uniforms, runaway work or broken geometry. */
object GlassCustomizationPolicy {
  const val MaximumStrength = 2f
  const val MaximumFrostPasses = 6
  fun strength(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, MaximumStrength) else 1f
  fun motion(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 1f
  fun frostPasses(value: Float): Int = (strength(value) * GlassFrostPolicy.Passes).roundToInt().coerceIn(0, MaximumFrostPasses)
}
