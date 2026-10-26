/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

/** Pure geometry for the selected navigation lens; no Compose state or frame loop. */
internal object NavigationGlassLensPolicy {
  const val SettledHeight = 44f
  const val PopHeight = 56f
  const val PopOffset = -6f
  const val MaxMagnification = 1.18f

  data class Geometry(
    val left: Float,
    val width: Float,
    val height: Float,
    val offsetY: Float,
    val magnification: Float,
  )

  fun geometry(
    left: Float,
    width: Float,
    active: Boolean,
  ): Geometry {
    val safeLeft = left.takeIf { it.isFinite() } ?: 0f
    val safeWidth = width.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    if (!active) return Geometry(safeLeft, safeWidth, SettledHeight, 0f, 1f)
    return Geometry(
      left = safeLeft,
      width = safeWidth,
      height = PopHeight,
      offsetY = PopOffset,
      magnification = MaxMagnification,
    )
  }
}
