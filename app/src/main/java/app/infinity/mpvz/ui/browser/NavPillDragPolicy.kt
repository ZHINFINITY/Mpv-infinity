/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import kotlin.math.abs

/** Pure hit-testing and snapping math for the browser navigation pill. */
internal object NavPillDragPolicy {
  const val TouchSlopPx = 8f
  const val FlingVelocityThresholdPx = 900f

  fun clampOffset(offset: Float, min: Float, max: Float): Float {
    val lo = min.coerceAtMost(max)
    val hi = max.coerceAtLeast(min)
    return offset.takeIf { it.isFinite() }?.coerceIn(lo, hi) ?: lo
  }

  fun nearestTabIndex(position: Float, tabCount: Int): Int {
    if (tabCount <= 1) return 0
    return position.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(0, tabCount - 1) ?: 0
  }

  fun hoveredTabIndex(position: Float, tabCount: Int): Int = nearestTabIndex(position, tabCount)

  /** Converts a finger displacement in pixels into a clamped tab-centre position. */
  fun positionAfterDrag(
    startCenterPx: Float,
    dragDeltaPx: Float,
    tabCenters: List<Float>,
    isRtl: Boolean = false,
  ): Float {
    if (tabCenters.isEmpty()) return 0f
    val fingerCenter = startCenterPx + dragDeltaPx
    return tabPositionAtX(fingerCenter, tabCenters, isRtl)
  }

  /** Applies a quick flick in physical screen coordinates, respecting RTL order. */
  fun flingSnappedIndex(
    position: Float,
    velocityPxPerSecond: Float,
    tabCount: Int,
    isRtl: Boolean = false,
    thresholdPxPerSecond: Float = FlingVelocityThresholdPx,
  ): Int {
    val nearest = nearestTabIndex(position, tabCount)
    if (!velocityPxPerSecond.isFinite() || abs(velocityPxPerSecond) < thresholdPxPerSecond) return nearest
    val direction = if (isRtl) -velocityPxPerSecond else velocityPxPerSecond
    return (nearest + if (direction > 0f) 1 else -1).coerceIn(0, (tabCount - 1).coerceAtLeast(0))
  }

  fun tabIndexAtX(x: Float, tabCenters: List<Float>, isRtl: Boolean = false): Int {
    if (tabCenters.isEmpty()) return -1
    val query = if (isRtl) (tabCenters.first() + tabCenters.last() - x) else x
    return tabCenters.indices.minWithOrNull(compareBy<Int> { abs(tabCenters[it] - query) }.thenBy { it }) ?: 0
  }

  fun tabPositionAtX(x: Float, tabCenters: List<Float>, isRtl: Boolean = false): Float {
    if (tabCenters.isEmpty()) return 0f
    if (tabCenters.size == 1) return 0f
    val query = if (isRtl) tabCenters.first() + tabCenters.last() - x else x
    if (query <= tabCenters.first()) return 0f
    if (query >= tabCenters.last()) return (tabCenters.lastIndex).toFloat()
    val upper = tabCenters.indexOfFirst { it >= query }.coerceAtLeast(1)
    val span = (tabCenters[upper] - tabCenters[upper - 1]).coerceAtLeast(0.001f)
    return (upper - 1) + ((query - tabCenters[upper - 1]) / span).coerceIn(0f, 1f)
  }

  fun crossedTouchSlop(distance: Float): Boolean = distance.isFinite() && abs(distance) >= TouchSlopPx

  private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
}
