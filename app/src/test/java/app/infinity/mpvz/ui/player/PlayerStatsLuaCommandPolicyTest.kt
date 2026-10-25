package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsLuaCommandPolicyTest {
  @Test
  fun offHidesEvenWhenVisiblePropertyIsNo() {
    val off = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 0,
      previousPage = 1,
      luaShown = false,
      consoleOpen = false,
    )
    assertTrue(off.toggleLua)
    assertTrue(off.clearOsd)
    assertNull(off.displayPage)
    assertEquals(false, off.luaVisible)
  }

  @Test
  fun offHidesVisibleLuaOnce() {
    val off = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 0,
      previousPage = 2,
      luaShown = true,
      consoleOpen = false,
    )
    assertTrue(off.toggleLua)
    assertTrue(off.clearOsd)
    assertEquals(false, off.luaVisible)
  }

  @Test
  fun offStillClearsOneshotAfterInterpolationRestore() {
    val restored = PlayerStatsLuaCommandPolicy.forStartup(1)!!
    assertTrue(restored.toggleLua)
    assertEquals(1, restored.displayPage)
    assertEquals(true, restored.luaVisible)
    val offAfterVanishedFlag =
      PlayerStatsLuaCommandPolicy.forChip(
        toPage = 0,
        previousPage = 1,
        luaShown = false,
        consoleOpen = false,
      )
    assertTrue(offAfterVanishedFlag.toggleLua)
    assertTrue(offAfterVanishedFlag.clearOsd)
    assertNull(offAfterVanishedFlag.displayPage)
  }

  @Test
  fun switchingLuaPagesDoesNotToggleAnAlreadyShownOverlay() {
    val page = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 3,
      previousPage = 1,
      luaShown = true,
      consoleOpen = false,
    )
    assertFalse(page.toggleLua)
    assertEquals(3, page.displayPage)
    assertEquals(true, page.luaVisible)
    assertFalse(page.clearOsd)
  }

  @Test
  fun enablingLuaFromOffUsesToggleThenDisplayPage() {
    val page = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 1,
      previousPage = 0,
      luaShown = false,
      consoleOpen = false,
    )
    assertTrue(page.toggleLua)
    assertEquals(1, page.displayPage)
    assertFalse(page.clearOsd)
  }

  @Test
  fun musicAndComposePagesNeverAskStartupToShowLua() {
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(0))
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(6))
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(7))
  }

  @Test
  fun musicHideUsesOffPathWithoutDisplayPage() {
    val hideShown = PlayerStatsLuaCommandPolicy.forMusicHide(luaShown = true, consoleOpen = false)
    assertTrue(hideShown.toggleLua)
    assertTrue(hideShown.clearOsd)
    assertNull(hideShown.displayPage)
    assertEquals(false, hideShown.luaVisible)
    val hideHidden = PlayerStatsLuaCommandPolicy.forMusicHide(luaShown = false, consoleOpen = false)
    assertFalse(hideHidden.toggleLua)
    assertTrue(hideHidden.clearOsd)
    assertNull(hideHidden.displayPage)
  }

  @Test
  fun videoResumeCopiesMpvRxToggleThenDisplayPage() {
    val resume = PlayerStatsLuaCommandPolicy.forVideoResume(1, luaShown = false)!!
    assertTrue(resume.toggleLua)
    assertEquals(1, resume.displayPage)
    assertEquals(true, resume.luaVisible)
    assertNull(PlayerStatsLuaCommandPolicy.forVideoResume(1, luaShown = true))
    assertNull(PlayerStatsLuaCommandPolicy.forVideoResume(0, luaShown = false))
  }

  @Test
  fun interpolationRestoreWaitsForPlaybackThenReappliesVoReconfigOnce() {
    assertFalse(PlayerStatsLuaCommandPolicy.shouldWaitForPlaybackBeforeRestore(false, true))
    assertFalse(PlayerStatsLuaCommandPolicy.shouldWaitForPlaybackBeforeRestore(true, false))
    assertTrue(PlayerStatsLuaCommandPolicy.shouldWaitForPlaybackBeforeRestore(true, true))
    assertFalse(PlayerStatsLuaCommandPolicy.shouldReapplyAfterVoReconfig(luaRestored = true, alreadyReapplied = false))
    assertFalse(PlayerStatsLuaCommandPolicy.shouldReapplyAfterVoReconfig(luaRestored = true, alreadyReapplied = true))
    assertFalse(PlayerStatsLuaCommandPolicy.shouldReapplyAfterVoReconfig(luaRestored = false, alreadyReapplied = false))
  }
}
