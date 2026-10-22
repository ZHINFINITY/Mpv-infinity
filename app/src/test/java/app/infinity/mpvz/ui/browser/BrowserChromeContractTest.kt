package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contracts for browser chrome fixes. */
class BrowserChromeContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  @Test
  fun sortViewOptionsDoNotAddNestedGlassPanes() {
    val source =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/dialogs/SortDialog.kt")
        .readText()
    val options = source.substringBefore("private fun SortTypeSelector")
    val sortTypeOptions =
      source.substringAfter("private fun SortTypeSelector")
        .substringBefore("private fun SortOrderSelector")
    assertTrue(options.contains("settingsGlassSurface(cornerRadius = 28.dp, independent = true)"))
    assertTrue(options.contains("Modifier.padding(horizontal = 8.dp, vertical = 4.dp)"))
    assertFalse(
      Regex("SegmentedButton\\(\\s*modifier = Modifier\\.liquidGlassPane")
        .containsMatchIn(source),
    )
    assertFalse(sortTypeOptions.contains("liquidGlassPane"))
  }

  @Test
  fun glassBrowserTopBarsAreEdgeToEdge() {
    val source =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/components/BrowserTopBar.kt")
        .readText()
    assertTrue(source.contains("Modifier.settingsGlassSurface(cornerRadius = 0.dp)"))
    assertFalse(source.contains("settingsGlassSurface(cornerRadius = 28.dp)"))
  }

  @Test
  fun browserBrandTitleUsesSharedInfinityGlyph() {
    val topBar =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/components/BrowserTopBar.kt")
        .readText()
    val folder =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/folderlist/FolderListScreen.kt")
        .readText()
    val filesystem =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/filesystem/FileSystemBrowserScreen.kt")
        .readText()
    assertTrue(topBar.contains("import app.infinity.mpvz.presentation.components.pullrefresh.InfinityLoadingIndicator"))
    assertTrue(topBar.contains("if (title == \"∞\")"))
    assertTrue(topBar.contains("InfinityLoadingIndicator(modifier = titleModifier, size = 32.dp)"))
    assertTrue(folder.contains("title = if (audiobookMode) \"Audiobooks\" else \"∞\""))
    assertTrue(filesystem.contains("\"∞\""))
    assertFalse(folder.contains("title = if (audiobookMode) \"Audiobooks\" else stringResource(app.infinity.mpvz.R.string.app_name)"))
  }
}
