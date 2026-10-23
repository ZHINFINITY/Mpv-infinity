/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
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
  val artwork: MiniPlayerArtworkTarget? = null,
  val viewportHeight: Float = 0f,
)

data class MiniPlayerArtworkTarget(
  val left: Float, val top: Float, val width: Float, val height: Float,
)

/** Geometry only, in screen coordinates: never retains an Activity or playback session. */
object MiniPlayerTransitionAnchor {
  @Volatile private var target: MiniPlayerTarget? = null

  fun publish(
    left: Float, top: Float, width: Float, height: Float, viewportWidth: Float,
    artworkLeft: Float? = null, artworkTop: Float? = null,
    artworkWidth: Float? = null, artworkHeight: Float? = null, viewportHeight: Float? = null,
  ) {
    if (!listOf(left, top, width, height, viewportWidth).all { it.isFinite() } ||
      width <= 0f || height <= 0f || viewportWidth <= 0f) return
    // An unmeasured cover is NOT the entire mini surface. Keep the square-cover fallback.
    val art = if (artworkLeft != null && artworkTop != null && artworkWidth != null && artworkHeight != null &&
      listOf(artworkLeft, artworkTop, artworkWidth, artworkHeight).all { it.isFinite() } &&
      artworkWidth > 0f && artworkHeight > 0f &&
      artworkLeft >= left - 2f && artworkTop >= top - 2f &&
      artworkLeft + artworkWidth <= left + width + 2f &&
      artworkTop + artworkHeight <= top + height + 2f
    ) MiniPlayerArtworkTarget(artworkLeft, artworkTop, artworkWidth, artworkHeight) else null
    target = MiniPlayerTarget(left, top, width, height, viewportWidth, art, viewportHeight ?: 0f)
  }

  fun invalidate() { target = null }

  fun forViewport(width: Float): MiniPlayerTarget? {
    // A zero-size first layout must not destroy the underlying Activity's measured anchor.
    if (!width.isFinite() || width <= 0f) return null
    val current = target ?: return null
    if (kotlin.math.abs(current.viewportWidth - width) > width * 0.05f) {
      target = null
      return null
    }
    return current
  }
}

data class MiniPlayerTransform(val x: Float, val y: Float, val scaleX: Float, val scaleY: Float)
data class MiniPlayerArtworkScale(val scaleX: Float, val scaleY: Float)
data class MiniPlayerArtworkFlight(val translationX: Float, val translationY: Float, val scaleX: Float, val scaleY: Float)

object MiniPlayerGesturePolicy {
  const val DefaultThreshold = 0.5f
  const val DefaultVelocityThresholdPxPerSecond = 900f

  fun nestedScrollDelta(state: MiniPlayerGestureState, availableY: Float): Float {
    if (!availableY.isFinite()) return 0f
    if (availableY <= 0f && state.progress <= 0f) return 0f
    return availableY
  }

  fun drag(start: MiniPlayerGestureState, deltaY: Float, extentPx: Float): MiniPlayerGestureState {
    if (!extentPx.isFinite() || extentPx <= 0f || !deltaY.isFinite()) return start
    return start.copy(progress = (start.progress + deltaY / extentPx).coerceIn(0f, 1f), settled = start.settled)
  }

  fun transform(progress: Float, width: Float, height: Float, originX: Float, originY: Float,
    target: MiniPlayerTarget?): MiniPlayerTransform {
    if (!listOf(width, height, originX, originY).all { it.isFinite() } || width <= 0f || height <= 0f)
      return MiniPlayerTransform(0f, 0f, 1f, 1f)
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val valid = target?.takeIf { listOf(it.left, it.top, it.width, it.height).all { v -> v.isFinite() } && it.width > 0f && it.height > 0f }
    val endWidth = valid?.width?.coerceIn(width * 0.1f, width) ?: width * 0.94f
    val endHeight = valid?.height?.coerceIn(height * 0.04f, height) ?: height * 0.10f
    val endX = valid?.let { it.left - originX }?.coerceIn(0f, width - endWidth) ?: width * 0.03f
    val endY = valid?.let { it.top - originY }?.coerceIn(0f, height - endHeight) ?: height * 0.85f
    return MiniPlayerTransform(endX * p, endY * p,
      1f + (endWidth / width - 1f) * p, 1f + (endHeight / height - 1f) * p)
  }

  /** Child and root layers BOTH use top-left origins and the same unwarped screen bounds. */
  fun artworkFlight(
    progress: Float, parent: MiniPlayerTransform, originX: Float, originY: Float,
    fullLeft: Float, fullTop: Float, fullWidth: Float, fullHeight: Float,
    target: MiniPlayerArtworkTarget?,
  ): MiniPlayerArtworkFlight {
    if (listOf(originX, originY, fullLeft, fullTop, fullWidth, fullHeight,
        parent.x, parent.y, parent.scaleX, parent.scaleY).any { !it.isFinite() } ||
      fullWidth <= 0f || fullHeight <= 0f || parent.scaleX <= 0f || parent.scaleY <= 0f || target == null ||
      listOf(target.left, target.top, target.width, target.height).any { !it.isFinite() } ||
      target.width <= 0f || target.height <= 0f) return MiniPlayerArtworkFlight(0f, 0f, 1f, 1f)
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val rootScaleX = parent.scaleX.coerceAtLeast(0.001f)
    val rootScaleY = parent.scaleY.coerceAtLeast(0.001f)
    val desiredLeft = fullLeft + (target.left - fullLeft) * p
    val desiredTop = fullTop + (target.top - fullTop) * p
    // Preserve square cover geometry even if measurement rounding makes the slot non-square.
    val targetSide = minOf(target.width, target.height)
    val desiredWidth = fullWidth + (targetSide - fullWidth) * p
    val desiredHeight = fullHeight + (targetSide - fullHeight) * p
    return MiniPlayerArtworkFlight(
      translationX = (desiredLeft - originX - parent.x) / rootScaleX - (fullLeft - originX),
      translationY = (desiredTop - originY - parent.y) / rootScaleY - (fullTop - originY),
      scaleX = desiredWidth / fullWidth / rootScaleX,
      scaleY = desiredHeight / fullHeight / rootScaleY,
    )
  }

  fun artworkScaleCorrection(
    progress: Float, parent: MiniPlayerTransform, artworkSize: Float, targetArtworkSize: Float,
  ): MiniPlayerArtworkScale {
    if (!listOf(artworkSize, targetArtworkSize, parent.scaleX, parent.scaleY).all { it.isFinite() } ||
      artworkSize <= 0f || targetArtworkSize <= 0f || parent.scaleX <= 0f || parent.scaleY <= 0f)
      return MiniPlayerArtworkScale(1f, 1f)
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val desired = 1f + (targetArtworkSize / artworkSize - 1f) * p
    val scaleX = desired / parent.scaleX.coerceAtLeast(0.001f)
    val scaleY = desired / parent.scaleY.coerceAtLeast(0.001f)
    return MiniPlayerArtworkScale(scaleX = scaleX, scaleY = scaleY)
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
