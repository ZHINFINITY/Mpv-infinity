/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import kotlin.math.roundToInt

/** Geometry applies only to the tiny UI copy, never the decoded video or its SurfaceView. */
object GlassScenePolicy {
  const val TransientGraceMs = 600L
  data class SourceRect(val left: Int, val top: Int, val width: Int, val height: Int)

  fun sourceRect(width: Int, height: Int, displayAspect: Double?): SourceRect? {
    if (width <= 0 || height <= 0) return null
    val aspect = displayAspect?.takeIf { it.isFinite() && it > 0.0 }
      ?: return SourceRect(0, 0, width, height)
    val viewport = width.toDouble() / height
    return if (viewport > aspect) {
      val contentWidth = (height * aspect).roundToInt().coerceIn(1, width)
      SourceRect((width - contentWidth) / 2, 0, contentWidth, height)
    } else {
      val contentHeight = (width / aspect).roundToInt().coerceIn(1, height)
      SourceRect(0, (height - contentHeight) / 2, width, contentHeight)
    }
  }

  data class CoverBounds(val left: Float, val top: Float, val width: Float, val height: Float)

  fun coverBounds(sourceWidth: Int, sourceHeight: Int, viewportWidth: Float, viewportHeight: Float): CoverBounds? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
      viewportWidth <= 0f || viewportHeight <= 0f) return null
    val scale = maxOf(viewportWidth / sourceWidth, viewportHeight / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    if (!width.isFinite() || !height.isFinite()) return null
    return CoverBounds((viewportWidth - width) / 2f, (viewportHeight - height) / 2f, width, height)
  }

  fun retainTransientFrame(elapsedMs: Long): Boolean = elapsedMs in 0 until TransientGraceMs
}
