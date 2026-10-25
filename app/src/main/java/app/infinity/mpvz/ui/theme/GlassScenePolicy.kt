/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import kotlin.math.roundToInt

/** Geometry applies only to the tiny UI copy, never the decoded video or its SurfaceView. */
object GlassScenePolicy {
  /**
   * A pane samples the full route content only when it is drawn in a different window
   * from the one recording it (a dialog or dropdown menu). An in-window pane would be
   * part of its own recording, so it keeps the isolated background scene.
   */
  fun samplesPopupContent(hasOwnerWindow: Boolean, sameWindowAsOwner: Boolean): Boolean =
    hasOwnerWindow && !sameWindowAsOwner

  const val TransientGraceMs = 600L
  const val MaxSceneDimension = 8192
  const val MaxScenePixels = 16L * 1024 * 1024

  fun validCaptureSize(width: Float, height: Float): Boolean =
    width.isFinite() && height.isFinite() &&
      width >= 1f && height >= 1f &&
      width <= MaxSceneDimension && height <= MaxSceneDimension &&
      width.toDouble() * height.toDouble() <= MaxScenePixels

  fun canDrawRecordedScene(isRecording: Boolean, hasValidDisplayList: Boolean): Boolean =
    !isRecording && hasValidDisplayList

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

  /**
   * Player ambience / interpolation must not flash a clear pane. Keep the last frosted
   * sample unless the surface or eligibility actually ended. Background stop
   * is not destroy: resume must reuse the last frost instead of a clear pane.
   */
  fun shouldKeepLastFrostedFrame(
    lifecycleStopped: Boolean,
    sourceChanged: Boolean,
    ineligible: Boolean,
  ): Boolean = !sourceChanged && !ineligible
}
