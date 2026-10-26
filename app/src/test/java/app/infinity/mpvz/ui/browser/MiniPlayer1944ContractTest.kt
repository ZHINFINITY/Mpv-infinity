/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.browser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner's mini player list (build 1944): glass, artist, marquee fades, progress line, spacing, FAB. */
class MiniPlayer1944ContractTest {
  private val root =
    generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun src(p: String) = File(root, "app/src/main/java/app/infinity/mpvz/$p").readText()

  @Test fun miniPlayerUsesGlassArtistAndEvenRoundTargets() {
    val m = src("ui/browser/components/MiniPlayer.kt")
    assertTrue(m.contains("BrowserGlassSurface("))
    assertFalse(m.contains("\"Playing Video\""))
    assertFalse(m.contains("\"Playing\" else \"Paused\""))
    assertTrue(m.contains("R.string.mini_player_unknown_artist"))
    assertEquals(2, Regex("basicMarquee\\(initialDelayMillis = 650\\)").findAll(m).count())
    assertEquals(2, Regex("miniMarqueeFade\\(size.width, 6.dp.toPx\\(\\), 16.dp.toPx\\(\\)\\)").findAll(m).count())
    assertTrue(Regex("Modifier.size\\(48.dp\\).clip\\(CircleShape\\)").findAll(m).count() >= 3)
    // Progress is drawn as a thin bottom line in the draw phase, not a full-height tint.
    assertTrue(m.contains("val trackY = size.height - 2.dp.toPx()"))
  }

  @Test fun quickPlayHidesWhileMiniPlayerShowsAndThereIsClearance() {
    assertTrue(src("ui/browser/fab/QuickPlayFab.kt").contains("&& !NavigationBarState.isMiniPlayerVisible"))
    assertTrue(src("MainActivity.kt").contains("NavigationBarState.navigationBarClearance + 10.dp"))
  }
}
