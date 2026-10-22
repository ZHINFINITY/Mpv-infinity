package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contracts for the browser surfaces that are easy to miss in nested destinations. */
class BrowserGlassCallersContractTest {
  private val projectRoot =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private val root = File(projectRoot, "app/src/main/java/app/infinity/mpvz/ui/browser")

  @Test
  fun nestedVideoFabUsesSharedConditionalGlassSurface() {
    val screen = File(root, "videolist/VideoListScreen.kt").readText()
    assertTrue(screen.contains("settingsGlassSurface(cornerRadius = 20.dp)"))
    assertTrue(screen.contains("settingsGlassContainerColor(MaterialTheme.colorScheme.primaryContainer)"))
    assertTrue(screen.contains("defaultElevation = if (liquidGlass) 0.dp"))
  }

  @Test
  fun browserTopBarAndVideoRowsDoNotPaintOpaqueGlassChildren() {
    val topBar = File(root, "components/BrowserTopBar.kt").readText()
    val card = File(root, "cards/VideoCard.kt").readText()
    assertTrue(topBar.contains("containerColor = Color.Transparent"))
    assertTrue(card.contains("if (liquidGlass)"))
    assertTrue(card.contains("BrowserGlassSurface"))
    assertTrue(card.contains("Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh"))
  }

  @Test
  fun folderGridUsesBalancedSpacingAndSwipeKeepsWatchedCallback() {
    val screen = File(root, "folderlist/FolderListScreen.kt").readText()
    val swipe = File(root, "cards/SwipeableFolderActions.kt").readText()
    assertTrue(screen.contains("horizontalArrangement = Arrangement.spacedBy(12.dp)"))
    assertTrue(screen.contains("verticalArrangement = Arrangement.spacedBy(12.dp)"))
    assertTrue(swipe.contains("currentOnWatchedChange(!currentIsWatched)"))
    assertFalse(swipe.contains("background(MaterialTheme.colorScheme.surface)"))
  }
}
