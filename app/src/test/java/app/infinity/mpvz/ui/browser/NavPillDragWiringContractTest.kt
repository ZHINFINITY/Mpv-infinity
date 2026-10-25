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
    val source =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt").readText()
    assertTrue(source.contains("awaitEachGesture"))
    assertTrue(source.contains("pass = PointerEventPass.Initial"))
    assertTrue(source.contains("awaitHorizontalTouchSlopOrCancellation"))
    assertTrue(source.contains("NavPillDragPolicy.positionAfterDrag"))
    assertTrue(source.contains("NavPillDragPolicy.flingSnappedIndex"))
    assertTrue(source.contains("visibleTabs.getOrNull(target)?.let(onTabSelected)"))
  }
}
