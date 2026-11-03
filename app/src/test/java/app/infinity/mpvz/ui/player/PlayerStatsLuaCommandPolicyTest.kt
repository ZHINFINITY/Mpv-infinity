package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsLuaCommandPolicyTest {
  @Test
  fun offDoesNotToggleWhenLuaIsAlreadyHidden() {
    val off = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 0,
      luaShown = false,
      consoleOpen = false,
    )
    assertFalse(off.toggleLua)
    assertNull(off.displayPage)
    assertEquals(false, off.luaVisible)
  }

  @Test
  fun offHidesVisibleLuaAndClearsItsLiveState() {
    val off = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 0,
      luaShown = true,
      consoleOpen = false,
    )
    assertTrue(off.toggleLua)
    assertNull(off.displayPage)
    assertEquals(false, off.luaVisible)
  }

  @Test
  fun choosingLuaPageUsesLiveVisibilityInsteadOfRememberedPage() {
    val hidden = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 3,
      luaShown = false,
      consoleOpen = false,
    )
    assertTrue(hidden.toggleLua)
    assertEquals(3, hidden.displayPage)
    assertEquals(true, hidden.luaVisible)

    val alreadyShown = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 3,
      luaShown = true,
      consoleOpen = false,
    )
    assertFalse(alreadyShown.toggleLua)
    assertEquals(3, alreadyShown.displayPage)
  }

  @Test
  fun startupRestoresPersistentOverlayOnlyWhenLiveCoreIsHidden() {
    val restored = PlayerStatsLuaCommandPolicy.forStartup(1, luaShown = false)!!
    assertTrue(restored.toggleLua)
    assertEquals(1, restored.displayPage)
    assertEquals(true, restored.luaVisible)

    val alreadyShown = PlayerStatsLuaCommandPolicy.forStartup(1, luaShown = true)!!
    assertFalse(alreadyShown.toggleLua)
    assertEquals(1, alreadyShown.displayPage)
  }

  @Test
  fun musicAndComposePagesNeverAskStartupToShowLua() {
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(0, luaShown = false))
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(6, luaShown = false))
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(7, luaShown = false))
  }

  @Test
  fun musicHideUsesOffPathWithoutDisplayPage() {
    val hideShown = PlayerStatsLuaCommandPolicy.forMusicHide(luaShown = true, consoleOpen = false)
    assertTrue(hideShown.toggleLua)
    assertNull(hideShown.displayPage)
    assertEquals(false, hideShown.luaVisible)
    val hideHidden = PlayerStatsLuaCommandPolicy.forMusicHide(luaShown = false, consoleOpen = false)
    assertFalse(hideHidden.toggleLua)
    assertNull(hideHidden.displayPage)
  }

  @Test
  fun videoResumeRestoresPersistentOverlayOnlyWhenHidden() {
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
    assertTrue(PlayerStatsLuaCommandPolicy.shouldReapplyAfterVoReconfig(luaRestored = true, alreadyReapplied = false))
    assertFalse(PlayerStatsLuaCommandPolicy.shouldReapplyAfterVoReconfig(luaRestored = true, alreadyReapplied = true))
    assertFalse(PlayerStatsLuaCommandPolicy.shouldReapplyAfterVoReconfig(luaRestored = false, alreadyReapplied = false))
  }
}
