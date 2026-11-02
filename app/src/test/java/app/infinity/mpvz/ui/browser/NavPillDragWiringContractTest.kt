package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guard for the bar gesture remaining connected to navigation. */
class NavPillDragWiringContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  @Test
  fun navigationBarOwnsSlopAwareDragAndNavigatesOnRelease() {
    val main = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt").readText()
    val gesture = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/NavPillDragGesture.kt").readText()
    // The pill wires the shared gesture outside its own horizontalScroll (outer = Initial first).
    val drag = main.indexOf(".then(dragModifier)")
    val scroll = main.indexOf(".horizontalScroll(pillScroll)")
    assertTrue(main.contains("Modifier.navPillDragGesture("))
    assertTrue(drag in 0 until scroll)
    assertTrue(main.contains("val latestVisibleTabs by rememberUpdatedState(visibleTabs)"))
    assertTrue(main.contains("val latestOnTabSelected by rememberUpdatedState(onTabSelected)"))
    assertTrue(main.contains("onRelease = { target, releasePosition ->"))
    assertTrue(main.contains("retainedFinalDragPosition = finalPosition"))
    assertTrue(main.contains("retainedFinalDragTarget = target"))
    assertTrue(main.contains("lensPosition.snapTo(finalPosition)"))
    assertTrue(main.contains("var lensSyncedToRelease by remember"))
    assertTrue(main.contains("lensSyncedToRelease = true"))
    assertTrue(main.contains("lensPosition.animateTo(targetPosition"))
    assertTrue(main.contains("retainedFinalDragTarget != null -> Unit"))
    assertTrue(main.contains("val layoutPosition = dragPosition ?: releaseVisualPosition ?: position"))
    assertTrue(main.contains("pager.settledPage == target"))
    assertTrue(main.contains("!pager.isScrollInProgress"))
    assertTrue(main.contains("!lensPosition.isRunning"))
    assertTrue(main.contains("retainedFinalDragPosition = null"))
    val pagerDragCleanup =
      main.substringAfter("LaunchedEffect(fingerDragging)").substringBefore("var hoveredTab by remember")
    assertTrue(pagerDragCleanup.contains("if (fingerDragging)"))
    assertTrue(pagerDragCleanup.contains("retainedFinalDragPosition = null"))
    assertTrue(pagerDragCleanup.contains("retainedFinalDragTarget = null"))
    assertTrue(gesture.contains("onRelease: (Int, Float) -> Unit"))
    assertTrue(gesture.contains("onRelease(target, releasePosition)"))
    assertTrue(!main.contains("onRelease = { target -> visibleTabs.getOrNull(target)?.let(onTabSelected) }"))
    // Build 1942 regression: slop on the Main pass lost the gesture to the inner scrollable.
    assertTrue(!gesture.contains("awaitHorizontalTouchSlopOrCancellation"))
    assertTrue(!gesture.contains("horizontalDrag("))
    assertTrue(gesture.contains("awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)"))
    assertTrue(Regex("awaitPointerEvent\\(PointerEventPass\\.Initial\\)").findAll(gesture).count() == 2)
    assertTrue(!Regex("awaitPointerEvent\\(\\)").containsMatchIn(gesture))
    assertTrue(gesture.contains("NavPillDragPolicy.positionAfterDrag"))
    assertTrue(gesture.contains("NavPillDragPolicy.flingSnappedIndex"))
  }

  @Test
  fun hoveredTabOpensDuringDragAndTheGestureIsNotRestartedByLayoutChanges() {
    val source =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt").readText()
    val hoverSync = source.substringAfter("var hoveredTab by remember").substringBefore("val visualPosition")
    assertTrue(hoverSync.contains("LaunchedEffect(targetIndex, lastIndex)"))
    assertTrue(hoverSync.contains("hoveredTab = targetIndex.toInt().coerceIn(0, lastIndex)"))
    // Labels fade in with tabFraction; the hovered tab must widen with the drag or they clip.
    assertTrue(source.contains("val releaseVisualPosition = if (synchronizedRelease) lensPosition.value else retainedFinalDragPosition"))
    assertTrue(source.contains("dragPosition ?: releaseVisualPosition ?: position"))
    assertTrue(source.contains("dragPosition ?: releaseVisualPosition ?: if"))
    assertTrue(source.contains("val tabFraction = if (dragPosition != null || retainedFinalDragTarget != null)"))
    assertTrue(source.contains("remember(layoutPosition, visibleTabs)"))
    // Tab centres change while tabs widen; keying pointerInput on them would cancel the drag.
    val gesture = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/NavPillDragGesture.kt").readText()
    assertTrue(gesture.contains("pointerInput(tabCount, selectedIndex, isRtl)"))
    assertTrue(gesture.contains("val flingThresholdPxPerSecond = 300.dp.toPx()"))
    assertTrue(gesture.contains("val autoScrollMarginPx = 56.dp.toPx()"))
    assertTrue(gesture.contains("thresholdPxPerSecond = flingThresholdPxPerSecond"))
    assertTrue(gesture.contains("keepLensVisible(scrollState, next, centers, autoScrollMarginPx)"))
    assertTrue(gesture.contains("marginPx = marginPx"))
    assertTrue(source.contains("tabCenters = { latestTabCenters }"))
    assertTrue(source.contains("rememberUpdatedState(tabCentersPx)"))
    assertTrue(!source.contains("pointerInput(visibleTabs, tabCentersPx"))
  }
}
