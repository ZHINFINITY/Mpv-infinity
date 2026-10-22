/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards actual destination callers so a shared glass token cannot hide a square backplate regression. */
class DestinationGlassCallersContractTest {
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }

  private fun source(path: String) = File(root, path).readText()

  @Test
  fun everyActualBrowserScaffoldDeclaresConditionalGlassContainer() {
    val browser = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser")
    browser.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
      val text = file.readText()
      Regex("(?<![A-Za-z0-9_])Scaffold\\(").findAll(text).forEach { call ->
        val arguments = text.substring(call.range.last + 1).take(250)
        assertTrue("Opaque scaffold at ${file.name}:${call.range.first}",
          arguments.contains("containerColor =") &&
            (arguments.contains("settingsGlassContainerColor(") ||
              arguments.contains("if (liquidGlass) Color.Transparent")))
      }
    }
  }

  @Test
  fun toggleQuickPlayCallersUseSupportedMatchedCornerProvider() {
    val browser = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser")
    browser.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
      val text = file.readText()
      Regex("ToggleFloatingActionButton\\(").findAll(text).forEach { call ->
        val arguments = text.substring(call.range.last + 1).take(400)
        if (arguments.contains("quickPlayFabContainerColor()")) {
          assertTrue("Unmatched Quick Play corners at ${file.name}", arguments.contains("quickPlayFabCornerRadius()"))
        }
      }
    }
  }

  @Test
  fun homeAndNestedFolderScaffoldsExposeTransparentGlassBranch() {
    val folderList = source("app/src/main/java/app/infinity/mpvz/ui/browser/folderlist/FolderListScreen.kt")
    val folderCard = source("app/src/main/java/app/infinity/mpvz/ui/browser/cards/FolderCard.kt")
    assertTrue(folderList.contains("containerColor = if (liquidGlass) Color.Transparent"))
    assertTrue(folderList.contains("VideoListScreen(") && folderList.contains("selectedFolderBucketId"))
    assertTrue(folderCard.contains("BrowserGlassSurface(") && folderCard.contains("if (liquidGlass) Color.Transparent"))
    assertTrue(folderCard.contains("onQuickPlay: (() -> Unit)?"))
  }

  @Test
  fun musicTabAndMusicCardsUseGlassWithoutRemovingNativeFallback() {
    val music = source("app/src/main/java/app/infinity/mpvz/ui/browser/music/MusicLibraryContent.kt")
    val cards = source("app/src/main/java/app/infinity/mpvz/ui/browser/music/SharedMusicComponents.kt")
    assertTrue(music.contains("if (liquidGlass) Color.Transparent"))
    assertTrue(music.contains("PrimaryScrollableTabRow") && music.contains("containerColor = Color.Transparent"))
    assertTrue(cards.contains("settingsGlassSurface(cornerRadius = 20.dp)"))
    assertTrue(cards.contains("containerColor = if (isSelected)"))
  }
}
