/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * Drag-to-switch for the bottom navigation pill.
 *
 * Every event is read on [PointerEventPass.Initial], which runs from the outermost node
 * inward, so the pill decides "horizontal drag" before the inner horizontalScroll or the tab
 * clickables can claim the gesture. (Build 1942 detected the slop on the Main pass, where the
 * inner scrollable consumed the move first and the pill drag never started on the phone.)
 *
 * A tap is never consumed, so the tab clickables still receive it. A mostly vertical move is
 * released untouched. Once the finger crosses the touch slop horizontally the gesture owns
 * the pointer: moves are consumed (cancelling the tab press and the scroll), the lens follows
 * the finger, and on release the fling-aware target tab is selected.
 */
internal fun Modifier.navPillDragGesture(
  tabCount: Int,
  selectedIndex: Int,
  isRtl: Boolean,
  tabCenters: () -> List<Float>,
  scrollState: ScrollState? = null,
  onDragPosition: (Float?) -> Unit,
  onHoverChanged: (Int) -> Unit,
  onRelease: (Int) -> Unit,
): Modifier = pointerInput(tabCount, selectedIndex, isRtl) {
  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    // Centres are frozen at drag start: tabs widen while hovered, and remapping the finger
    // against moving centres would make the lens run away from it.
    val centers = tabCenters()
    val startScrollOffset = scrollState?.value?.toFloat() ?: 0f
    val startCenter = NavPillDragPolicy.contentXFromViewport(
      viewportX = down.position.x,
      scrollOffsetPx = startScrollOffset,
      viewportSizePx = scrollState?.viewportSize?.toFloat() ?: 0f,
      isRtl = isRtl,
    )
    var lastScrollOffset = startScrollOffset
    val slop = viewConfiguration.touchSlop
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var hovered = selectedIndex
    var totalDelta = 0f

    fun update(delta: Float) {
      val currentScrollOffset = scrollState?.value?.toFloat() ?: lastScrollOffset
      totalDelta += (if (isRtl) -delta else delta) + (currentScrollOffset - lastScrollOffset)
      lastScrollOffset = currentScrollOffset
      var next = NavPillDragPolicy.positionAfterDrag(startCenter, totalDelta, centers)
      onDragPosition(next)
      keepLensVisible(scrollState, next, centers)
      val afterAutoScroll = scrollState?.value?.toFloat() ?: lastScrollOffset
      val autoScrollDelta = afterAutoScroll - lastScrollOffset
      if (autoScrollDelta != 0f) {
        totalDelta += autoScrollDelta
        lastScrollOffset = afterAutoScroll
        next = NavPillDragPolicy.positionAfterDrag(startCenter, totalDelta, centers)
        onDragPosition(next)
      }
      val now = NavPillDragPolicy.hoveredTabIndex(next, tabCount)
      if (now != hovered) {
        hovered = now
        onHoverChanged(now)
      }
    }

    // 1. Slop detection on the Initial pass; nothing is consumed until the drag is ours.
    var dragging = false
    while (!dragging) {
      val event = awaitPointerEvent(PointerEventPass.Initial)
      val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
      if (!change.pressed || change.isConsumed) return@awaitEachGesture // tap, or claimed above
      tracker.addPosition(change.uptimeMillis, change.position)
      val dx = change.position.x - down.position.x
      val dy = change.position.y - down.position.y
      when (NavPillDragPolicy.classifySlop(abs(dx), abs(dy), slop)) {
        NavPillDragPolicy.SlopDecision.Undecided -> Unit
        NavPillDragPolicy.SlopDecision.Vertical -> return@awaitEachGesture
        NavPillDragPolicy.SlopDecision.Horizontal -> {
          dragging = true
          change.consume()
          update(dx - if (dx > 0f) slop else -slop)
        }
      }
    }

    // 2. The pill owns the pointer until it lifts.
    var released = false
    try {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        tracker.addPosition(change.uptimeMillis, change.position)
        if (!change.pressed) {
          change.consume()
          released = true
          break
        }
        val delta = change.positionChange().x
        change.consume()
        if (delta != 0f) update(delta)
      }
    } finally {
      val velocity = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
      val finalScrollOffset = scrollState?.value?.toFloat() ?: lastScrollOffset
      val finalDelta = totalDelta + (finalScrollOffset - lastScrollOffset)
      val releasePosition = NavPillDragPolicy.positionAfterDrag(startCenter, finalDelta, centers)
      onDragPosition(null)
      if (released) {
        val target = NavPillDragPolicy.flingSnappedIndex(releasePosition, velocity, tabCount, isRtl)
        onHoverChanged(target)
        onRelease(target)
      } else {
        onHoverChanged(selectedIndex)
      }
    }
  }
}

/** Scrolls an overflowing pill so the dragged lens never leaves the visible strip. */
private fun keepLensVisible(scrollState: ScrollState?, position: Float, centers: List<Float>) {
  if (scrollState == null || scrollState.maxValue <= 0 || centers.isEmpty()) return
  val floor = position.toInt().coerceIn(0, centers.lastIndex)
  val ceil = (floor + 1).coerceAtMost(centers.lastIndex)
  val fraction = (position - floor).coerceIn(0f, 1f)
  val lensCenter = centers[floor] + (centers[ceil] - centers[floor]) * fraction
  val shift = NavPillDragPolicy.autoScrollDelta(
    lensCenter = lensCenter,
    scroll = scrollState.value.toFloat(),
    viewport = scrollState.viewportSize.toFloat(),
  )
  if (shift != 0f) scrollState.dispatchRawDelta(shift)
}
