package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsLuaOverlayPolicyTest {
  private fun input(
    requested: Int,
    visible: Boolean = false,
    page: Int = 0,
    sheet: Boolean = false,
    music: Boolean = false,
  ) = PlayerStatsLuaOverlayPolicy.Input(requested, visible, page, sheet, music)

  private fun args(result: PlayerStatsLuaOverlayPolicy.Result) = result.commands.map { it.args }

  @Test
  fun offHidesEntireOverlayWithASingleToggle() {
    val off = PlayerStatsLuaOverlayPolicy.decide(input(requested = 0, visible = true, page = 1))
    assertFalse(off.visible)
    assertEquals(0, off.page)
    assertEquals(listOf(listOf("script-binding", "stats/display-stats-toggle")), args(off))
  }

  @Test
  fun repeatedOffDoesNotRetoggleAHiddenOverlay() {
    val off = PlayerStatsLuaOverlayPolicy.decide(input(requested = 0, visible = false, page = 0))
    assertFalse(off.visible)
    assertTrue(off.commands.isEmpty())
  }

  @Test
  fun pageTwoReplacesPageOneWithoutAToggle() {
    val next = PlayerStatsLuaOverlayPolicy.decide(input(requested = 2, visible = true, page = 1))
    assertTrue(next.visible)
    assertEquals(2, next.page)
    assertEquals(listOf(listOf("script-binding", "stats/display-page-2")), args(next))
  }

  @Test
  fun interpolationRestoreShowsOnePageOnAFreshHiddenCore() {
    val restore = PlayerStatsLuaOverlayPolicy.decide(input(requested = 1, visible = false, page = 0))
    assertTrue(restore.visible)
    assertEquals(listOf(listOf("script-binding", "stats/display-page-1")), args(restore))
  }

  @Test
  fun sheetsAndMusicHideStatsEvenIfAPageIsPersisted() {
    val sheet = PlayerStatsLuaOverlayPolicy.decide(input(requested = 1, visible = true, page = 1, sheet = true))
    assertFalse(sheet.visible)
    assertEquals(listOf(listOf("script-binding", "stats/display-stats-toggle")), args(sheet))
    val music = PlayerStatsLuaOverlayPolicy.decide(input(requested = 2, visible = true, page = 2, music = true))
    assertFalse(music.visible)
    assertEquals(listOf(listOf("script-binding", "stats/display-stats-toggle")), args(music))
  }

  @Test
  fun sameVisiblePageIsIdempotent() {
    val same = PlayerStatsLuaOverlayPolicy.decide(input(requested = 1, visible = true, page = 1))
    assertTrue(same.visible)
    assertTrue(same.commands.isEmpty())
  }
}
