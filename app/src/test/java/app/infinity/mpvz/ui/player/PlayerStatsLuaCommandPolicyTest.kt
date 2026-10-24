package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsLuaCommandPolicyTest {
  @Test
  fun offDoesNotToggleWhenLuaIsAlreadyHidden() {
    val off = PlayerStatsLuaCommandPolicy.forChip(toPage = 0, luaShown = false, consoleOpen = false)
    assertFalse(off.toggleLua)
    assertNull(off.displayPage)
    assertEquals(false, off.luaVisible)
  }

  @Test
  fun offHidesVisibleLuaOnce() {
    val off = PlayerStatsLuaCommandPolicy.forChip(toPage = 0, luaShown = true, consoleOpen = false)
    assertTrue(off.toggleLua)
    assertEquals(false, off.luaVisible)
    assertFalse(PlayerStatsLuaCommandPolicy.forChip(toPage = 0, luaShown = false, consoleOpen = false).toggleLua)
  }

  @Test
  fun interpolationRestartRestoresLuaWithoutInvertingHiddenState() {
    val restored = PlayerStatsLuaCommandPolicy.forStartup(2)!!
    assertTrue(restored.toggleLua)
    assertEquals(2, restored.displayPage)
    assertEquals(true, restored.luaVisible)
    val offAfterVanishedOverlay =
      PlayerStatsLuaCommandPolicy.forChip(toPage = 0, luaShown = false, consoleOpen = false)
    assertFalse(offAfterVanishedOverlay.toggleLua)
  }

  @Test
  fun switchingLuaPagesDoesNotToggleAnAlreadyShownOverlay() {
    val page = PlayerStatsLuaCommandPolicy.forChip(toPage = 3, luaShown = true, consoleOpen = false)
    assertFalse(page.toggleLua)
    assertEquals(3, page.displayPage)
    assertEquals(true, page.luaVisible)
  }

  @Test
  fun musicAndComposePagesNeverAskStartupToShowLua() {
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(0))
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(6))
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(7))
  }
}
