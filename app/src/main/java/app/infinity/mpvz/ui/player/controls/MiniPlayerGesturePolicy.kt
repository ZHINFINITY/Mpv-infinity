/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

/** Pure policy for the reversible full-player -> browser mini-player handoff. */
data class MiniPlayerGestureState(
  val progress: Float = 0f,
  val settled: MiniPlayerSettle = MiniPlayerSettle.Full,
)

enum class MiniPlayerSettle { Full, Mini }

data class MiniPlayerTarget(
  val left: Float, val top: Float, val width: Float, val height: Float,
  val viewportWidth: Float,
)

/** Geometry only: never retains an Activity, artwork, queue or playback session. */
object MiniPlayerTransitionAnchor {
  @Volatile private var target: MiniPlayerTarget? = null

  fun publish(left: Float, top: Float, width: Float, height: Float, viewportWidth: Float) {
    if (listOf(left, top, width, height, viewportWidth).all { it.isFinite() } &&
      width > 0f && height > 0f && viewportWidth > 0f) {
      target = MiniPlayerTarget(left, top, width, height, viewportWidth)
    }
  }

  fun forViewport(width: Float): MiniPlayerTarget? = target?.takeIf {
    width.isFinite() && width > 0f && kotlin.math.abs(it.viewportWidth - width) <= width * 0.05f
  }
}

data class MiniPlayerTransform(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float)

object MiniPlayerGesturePolicy {
  const val DefaultThreshold = 0.5f
  const val DefaultVelocityThresholdPxPerSecond = 900f

  fun drag(start: MiniPlayerGestureState, deltaY: Float, extentPx: Float): MiniPlayerGestureState {
    if (!extentPx.isFinite() || extentPx <= 0f || !deltaY.isFinite()) return start
    return start.copy(progress = (start.progress + deltaY / extentPx).coerceIn(0f, 1f), settled = start.settled)
  }

  fun transform(progress: Float, width: Float, height: Float, originX: Float, originY: Float,
    target: MiniPlayerTarget?): MiniPlayerTransform {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
      return MiniPlayerTransform(0f, 0f, 1f, 1f)
    }
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val endWidth = target?.width?.coerceIn(width * 0.1f, width) ?: width * 0.94f
    val endHeight = target?.height?.coerceIn(height * 0.04f, height) ?: height * 0.10f
    val endX = target?.let { it.left - originX }?.coerceIn(0f, width - endWidth) ?: width * 0.03f
    val endY = target?.let { it.top - originY }?.coerceIn(0f, height - endHeight) ?: height * 0.85f
    return MiniPlayerTransform(endX * p, endY * p,
      1f + (endWidth / width - 1f) * p, 1f + (endHeight / height - 1f) * p)
  }

  fun cancel(): MiniPlayerGestureState = MiniPlayerGestureState()

  fun settle(
    state: MiniPlayerGestureState,
    velocityY: Float,
    threshold: Float = DefaultThreshold,
    velocityThresholdPxPerSecond: Float = DefaultVelocityThresholdPxPerSecond,
  ): MiniPlayerGestureState {
    val collapse = velocityY >= velocityThresholdPxPerSecond ||
      (velocityY > -velocityThresholdPxPerSecond && state.progress >= threshold)
    return MiniPlayerGestureState(
      progress = if (collapse) 1f else 0f,
      settled = if (collapse) MiniPlayerSettle.Mini else MiniPlayerSettle.Full,
    )
  }

}
