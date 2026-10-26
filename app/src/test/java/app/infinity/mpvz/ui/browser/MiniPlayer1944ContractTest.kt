/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner mini-player checklist: global glass, accessible controls, spacing, progress and FAB lanes. */
class MiniPlayer1944ContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun src(p: String) = File(root, "app/src/main/java/app/infinity/mpvz/$p").readText()

  @Test fun miniPlayerUsesGlobalGlassAndAccessibleRoundTargets() {
    val m = src("ui/browser/components/MiniPlayer.kt")
    assertTrue(m.contains("BrowserGlassSurface("))
    assertTrue(m.contains("opacity = glassOpacity"))
    assertTrue(m.contains("highlight = glassHighlight"))
    assertTrue(m.contains("appearance.glassSurfaceOpacity.collectAsState()"))
    assertTrue(m.contains("appearance.glassSurfaceHighlight.collectAsState()"))
    assertTrue(m.contains("val settingsGlassEnabled =" ) || m.contains("val glassEnabled = settingsGlassEnabled()"))
    assertFalse(m.contains("\"Playing Video\""))
    assertFalse(m.contains("\"Playing\" else \"Paused\""))
    assertTrue(m.contains("R.string.mini_player_unknown_artist"))
    assertEquals(2, Regex("basicMarquee\\(initialDelayMillis = 650\\)").findAll(m).count())
    assertEquals(2, Regex("miniMarqueeFade\\(size.width, 6.dp.toPx\\(\\), 16.dp.toPx\\(\\)\\)").findAll(m).count())
    assertEquals(5, Regex("(?m)^\\s*MiniPlayerGlassButton\\(").findAll(m).count())
    assertTrue(m.contains("shape = CircleShape"))
    assertTrue(m.contains("shadowElevation = if (pressed && glassEnabled) 4.dp.toPx() * motionAmount else 0f"))
    assertTrue(m.contains("LocalMotionPolicy.current.reduceMotion"))
    assertTrue(m.contains("LocalGlassCustomization.current.motion.coerceIn(0f, 1f)"))
    assertTrue(m.contains("horizontalArrangement = Arrangement.spacedBy(8.dp)"))
    assertTrue(m.contains("R.string.downloads_pause"))
    assertTrue(m.contains("R.string.ui_play"))
    assertTrue(m.contains("R.string.ui_next"))
    assertTrue(m.contains("R.string.ui_close"))
    assertEquals(2, Regex("drawRoundRect\\(color = primaryContainerColor.copy\\(alpha = 0.72f\\)").findAll(m).count())
    assertTrue(m.contains("val trackY = size.height - 2.dp.toPx()"))
  }

  @Test fun everyQuickPlayRouteHidesWhenTheMiniPlayerOwnsTheBrowserLane() {
    val fab = src("ui/browser/fab/QuickPlayFab.kt")
    assertTrue(fab.contains("showPreferenceEnabled && !NavigationBarState.isMiniPlayerVisible"))
    assertTrue(fab.contains("quickPlayFabAllowed(showQuickPlayFabPreference)"))
    assertTrue(fab.contains("fun QuickPlayMenuVisibilityEffect(available: Boolean"))
    assertTrue(fab.contains("LaunchedEffect(available)"))

    val routes = listOf(
      "ui/browser/filesystem/FileSystemBrowserScreen.kt",
      "ui/browser/folderlist/FolderListScreen.kt",
      "ui/browser/jellyfin/JellyfinContent.kt",
      "ui/browser/medialibrary/MediaLibraryContent.kt",
      "ui/browser/music/MusicLibraryContent.kt",
      "ui/browser/recentlyplayed/RecentlyPlayedScreen.kt",
      "ui/browser/videolist/VideoListScreen.kt",
    )
    routes.forEach { path ->
      assertTrue("Quick Play lane not gated in $path", src(path).contains("quickPlayFabAllowed(showQuickPlayFabPreference)"))
    }
    routes.dropLast(1).forEach { path ->
      assertTrue("stale Quick Play menu not cleared in $path", src(path).contains("QuickPlayMenuVisibilityEffect(showQuickPlayFab)"))
    }
    assertTrue(src("MainActivity.kt").contains("NavigationBarState.navigationBarClearance + 10.dp"))
  }
}
