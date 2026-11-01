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
    // Glass top/selection bars run edge to edge; only classic mode rounds the selection bar.
    assertFalse(topBar.contains("settingsGlassSurface(cornerRadius = 28.dp)"))
    assertFalse(topBar.contains(").clip(RoundedCornerShape(28.dp))"))
    assertTrue(topBar.contains("Modifier.settingsGlassSurface(cornerRadius = 0.dp)\n        } else {\n          Modifier.clip(RoundedCornerShape(28.dp))"))
    // The home logo keeps its title modifier, semantics and 40 dp size; it is now also the only
    // infinity mark that follows "Animate infinity logo" (pull-to-refresh always loops).
    assertTrue(topBar.contains("InfinityLoadingIndicator(\n          modifier = titleModifier.semantics { contentDescription = \"Mpv∞\" },\n          size = 40.dp,\n          animate = animateInfinityLogo,\n        )"))
    assertTrue(folder.contains("title = if (audiobookMode) \"Audiobooks\" else \"∞\""))
    assertTrue(filesystem.contains("\"∞\""))
    assertFalse(folder.contains("title = if (audiobookMode) \"Audiobooks\" else stringResource(app.infinity.mpvz.R.string.app_name)"))
  }

  @Test
  fun audioMiniPlayerUsesQueueArtistBeforeUnknownFallback() {
    val source =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/components/AudioMiniPlayer.kt")
        .readText()
    val artistStart = source.indexOf("val artist =")
    val titleStart = source.indexOf("val title =", artistStart)
    val artistExpression = source.substring(artistStart, titleStart)
    assertTrue(artistExpression.indexOf("rawArtist") < artistExpression.indexOf("rawAlbum"))
    assertTrue(artistExpression.indexOf("rawAlbum") < artistExpression.indexOf("sessionState.currentItem?.artist"))
    assertTrue(artistExpression.indexOf("sessionState.currentItem?.artist") < artistExpression.indexOf("context.getString"))
  }

  @Test
  fun directQuickPlayGateDoesNotHideNonDirectActionMenusWithMiniPlayer() {
    val helper = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/fab/QuickPlayFab.kt").readText()
    assertTrue(helper.contains("showPreferenceEnabled && !NavigationBarState.isMiniPlayerVisible"))
    assertTrue(helper.contains("quickPlayFabAllowed(showQuickPlayFabPreference)"))
    val screens =
      listOf(
        "ui/browser/filesystem/FileSystemBrowserScreen.kt",
        "ui/browser/folderlist/FolderListScreen.kt",
        "ui/browser/recentlyplayed/RecentlyPlayedScreen.kt",
        "ui/browser/medialibrary/MediaLibraryContent.kt",
        "ui/browser/jellyfin/JellyfinContent.kt",
        "ui/browser/music/MusicLibraryContent.kt",
      )
    for (path in screens) {
      val source = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()
      assertTrue("preference-only menu visibility in $path", source.contains("val showQuickPlayFab = showQuickPlayFabPreference"))
      assertTrue("separate direct guard in $path", source.contains("quickPlayFabAllowed(showQuickPlayFabPreference)"))
      assertTrue("non-direct menu remains open in $path", source.contains("QuickPlayMenuVisibilityEffect(showQuickPlayFab && !quickPlayFabDirect)"))
      assertTrue("direct Quick Play remains mini-player-gated in $path", source.contains("(!quickPlayFabDirect || quickPlayFabDirectAllowed)"))
    }
    val music = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/music/MusicLibraryContent.kt").readText()
    assertTrue("Playlists FAB remains independent", music.contains("visible = isPlaylistFabShouldBeVisible"))
  }

  @Test
  fun settingsTopBarsAreEdgeToEdgeWhenGlassIsEnabled() {
    val source =
      File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsScaffold.kt")
        .readText()
    assertTrue(source.contains("modifier.settingsGlassSurface(cornerRadius = 0.dp)"))
    assertFalse(source.contains("modifier.settingsGlassSurface(cornerRadius = 28.dp)"))
  }
}
