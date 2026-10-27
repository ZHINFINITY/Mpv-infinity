/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the actual Quick Play menu call sites rather than maintaining a detached inventory. */
class QuickPlayGlassContractTest {
  private val styledMenuItem =
    Regex(
      "FloatingActionButtonMenuItem\\(\\s+modifier = Modifier\\.settingsGlassSurface\\(cornerRadius = 20\\.dp\\),\\s+containerColor = settingsGlassContainerColor\\(MaterialTheme\\.colorScheme\\.surfaceContainerHigh\\),\\s+contentColor = MaterialTheme\\.colorScheme\\.onSurface",
      RegexOption.DOT_MATCHES_ALL,
    )
  private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private val filesystem =
    File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/filesystem/FileSystemBrowserScreen.kt")
      .readText()
  private val standaloneFab =
    File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/fab/QuickPlayFab.kt")
      .readText()
  private val music =
    File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/music/MusicLibraryContent.kt")
      .readText()
  private val settingsGlass =
    File(root, "app/src/main/java/app/infinity/mpvz/ui/preferences/components/SettingsGlass.kt")
      .readText()
  private val liquidGlassPane =
    File(root, "app/src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt")
      .readText()

  @Test
  fun filesystemQuickPlayOptionsUseAmbientGlassAndPreserveActions() {
    assertEquals(3, filesystem.countOccurrences("FloatingActionButtonMenuItem("))
    assertTrue(filesystem.countOccurrences("settingsGlassSurface(cornerRadius = 20.dp)") >= 3)
    assertEquals(3, styledMenuItem.findAll(filesystem).count())
    assertTrue(filesystem.contains("if (!quickPlayFabDirect) {"))
    assertTrue(filesystem.contains("filePicker.launch(arrayOf(\"video/*\"))"))
    assertTrue(filesystem.contains("getRecentlyPlayed("))
    assertTrue(filesystem.contains("showLinkDialog.value = true"))
    assertTrue(filesystem.contains("containerCornerRadius = app.infinity.mpvz.ui.browser.fab.quickPlayFabCornerRadius()"))
    assertTrue(standaloneFab.contains("ToggleFloatingActionButtonDefaults.containerCornerRadius()"))
    assertTrue(standaloneFab.contains("return if (glass) { _: Float -> 20.dp } else original"))
  }

  @Test
  fun standaloneQuickPlayUsesMatchingNativeShapeAndRealPlaybackCallback() {
    assertTrue(standaloneFab.contains("shape = RoundedCornerShape(20.dp)"))
    assertTrue(standaloneFab.contains("RecentlyPlayedOps.getLastPlayedEntity()"))
    assertTrue(standaloneFab.contains("MediaUtils.playFile("))
  }

  @Test
  fun musicQuickPlayOptionsUseAmbientGlassAndPreserveShuffleBehavior() {
    assertEquals(2, music.countOccurrences("FloatingActionButtonMenuItem("))
    assertTrue(music.countOccurrences("settingsGlassSurface(cornerRadius = 20.dp)") >= 2)
    assertEquals(2, styledMenuItem.findAll(music).count())
    assertTrue(music.contains("if (!quickPlayFabDirect) {"))
    assertTrue(music.contains("playAllSongs(context, songs, shuffle = false)"))
    assertTrue(music.contains("playAllSongs(context, songs, shuffle = true)"))
  }

  @Test
  fun playlistCreationRemainsVisibleWithMiniPlayerWithoutChangingQuickPlayPolicy() {
    val playlistFab = music.substringAfter("} else if (isPlaylistsTab) {")
      .substringBefore("} else if (songs.isNotEmpty()) {")
    val quickPlayFab = music.substringAfter("} else if (songs.isNotEmpty()) {")
    val mainScreen = File(root, "app/src/main/java/app/infinity/mpvz/ui/browser/MainScreen.kt").readText()
    assertTrue(music.contains("val isFabShouldBeVisible = showQuickPlayFab && isPlaylistFabShouldBeVisible"))
    assertTrue(playlistFab.contains("visible = isPlaylistFabShouldBeVisible"))
    assertTrue(playlistFab.contains("onCheckedChange = { showCreatePlaylistDialog = true }"))
    assertTrue(playlistFab.contains("Modifier.padding(bottom = (navigationBarHeight - 16.dp).coerceAtLeast(0.dp))"))
    assertTrue(mainScreen.contains("val miniPlayerNavClearance = if (isMiniPlayerVisible && isPortrait && !isTablet) 96.dp else 0.dp"))
    assertTrue(mainScreen.contains("val contentBottomPadding = remember(miniPlayerNavClearance) { 88.dp + miniPlayerNavClearance }"))
    assertTrue(mainScreen.contains("LocalNavigationBarHeight provides contentBottomPadding"))
    assertTrue(quickPlayFab.contains("visible = isFabShouldBeVisible"))
    assertTrue(music.contains("QuickPlayMenuVisibilityEffect(showQuickPlayFab)"))
  }

  @Test
  fun glassModifierKeepsNativeFallbackWhenDisabled() {
    assertTrue(settingsGlass.contains("liquidGlassPane("))
    assertTrue(settingsGlass.contains("enabled = enabled"))
    assertTrue(liquidGlassPane.contains("if (!enabled) return this"))
    assertTrue(settingsGlass.contains("return if (enabled)"))
  }

  private fun String.countOccurrences(needle: String): Int = windowed(needle.length).count { it == needle }
}
