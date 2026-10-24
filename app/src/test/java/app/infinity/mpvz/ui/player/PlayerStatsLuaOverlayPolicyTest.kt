package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerStatsLuaOverlayPolicyTest {
  @Before
  fun resetDepth() {
    PlayerStatsLuaOverlayPolicy.reset()
  }

  private fun input(
    requested: Int,
    visible: Boolean = false,
    page: Int = 0,
    depth: Int = if (visible) 1 else 0,
    sheet: Boolean = false,
    music: Boolean = false,
    forceReset: Boolean = false,
    more: Boolean = false,
  ) = PlayerStatsLuaOverlayPolicy.Input(
    requestedPage = requested,
    currentlyVisible = visible,
    currentlyPage = page,
    overlayDepth = depth,
    sheetOrPanelOpen = sheet,
    isMusic = music,
    forceReset = forceReset,
    senderIsMoreSheet = more,
  )

  private fun args(result: PlayerStatsLuaOverlayPolicy.Result) = result.commands.map { it.args }

  @Test
  fun offHidesEveryStackedOverlay() {
    val off = PlayerStatsLuaOverlayPolicy.decide(
      input(requested = 0, visible = true, page = 1, depth = 2),
    )
    assertFalse(off.visible)
    assertEquals(0, off.page)
    assertEquals(0, off.overlayDepth)
    assertEquals(
      listOf(
        listOf("script-binding", "stats/display-stats-toggle"),
        listOf("script-binding", "stats/display-stats-toggle"),
      ),
      args(off),
    )
  }

  @Test
  fun cleanCoreRestoreShowsOnePageWithoutABlindToggle() {
    val restore = PlayerStatsLuaOverlayPolicy.decide(
      input(requested = 1, visible = false, page = 0, depth = 0, forceReset = true),
    )
    assertTrue(restore.visible)
    assertEquals(1, restore.page)
    assertEquals(listOf(listOf("script-binding", "stats/display-page-1")), args(restore))
  }

  @Test
  fun interpolationRestoreHidesKnownStackThenShowsOnePage() {
    val restore = PlayerStatsLuaOverlayPolicy.decide(
      input(requested = 1, visible = true, page = 1, depth = 2, forceReset = true),
    )
    assertTrue(restore.visible)
    assertEquals(1, restore.page)
    assertEquals(
      listOf(
        listOf("script-binding", "stats/display-stats-toggle"),
        listOf("script-binding", "stats/display-stats-toggle"),
        listOf("script-binding", "stats/display-page-1"),
      ),
      args(restore),
    )
  }

  @Test
  fun pageTwoReplacesPageOneByHidingFirst() {
    val next = PlayerStatsLuaOverlayPolicy.decideChip(
      fromPage = 1,
      toPage = 2,
      overlayDepth = 1,
      currentlyVisible = true,
    )
    assertTrue(next.visible)
    assertEquals(2, next.page)
    assertEquals(
      listOf(
        listOf("script-binding", "stats/display-stats-toggle"),
        listOf("script-binding", "stats/display-page-2"),
      ),
      args(next),
    )
  }

  @Test
  fun moreSenderDoesNotEmitCompetingCommands() {
    val more = PlayerStatsLuaOverlayPolicy.decide(
      input(requested = 2, visible = true, page = 1, more = true),
    )
    assertTrue(more.commands.isEmpty())
  }

  @Test
  fun musicHidesEvenIfAPageIsPersisted() {
    val music = PlayerStatsLuaOverlayPolicy.decide(
      input(requested = 2, visible = true, page = 2, music = true),
    )
    assertFalse(music.visible)
    assertEquals(listOf(listOf("script-binding", "stats/display-stats-toggle")), args(music))
  }

  @Test
  fun sameVisiblePageIsIdempotent() {
    val same = PlayerStatsLuaOverlayPolicy.decide(input(requested = 1, visible = true, page = 1, depth = 1))
    assertTrue(same.visible)
    assertTrue(same.commands.isEmpty())
  }

  @Test
  fun applyChipRecordsDepthSoOffCanHideStacks() {
    PlayerStatsLuaOverlayPolicy.applyChip(fromPage = 0, toPage = 1)
    assertEquals(1, PlayerStatsLuaOverlayPolicy.overlayDepth)
    val off = PlayerStatsLuaOverlayPolicy.applyChip(fromPage = 1, toPage = 0)
    assertEquals(0, off.overlayDepth)
    assertEquals(listOf(listOf("script-binding", "stats/display-stats-toggle")), args(off))
  }
}
