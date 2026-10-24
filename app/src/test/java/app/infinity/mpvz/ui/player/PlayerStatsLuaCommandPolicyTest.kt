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
      previousPage = 1,
      luaShown = false,
      consoleOpen = false,
    )
    assertFalse(off.toggleLua)
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
    assertEquals(false, off.luaVisible)
    assertFalse(
      PlayerStatsLuaCommandPolicy.forChip(
        toPage = 0,
        previousPage = 2,
        luaShown = false,
        consoleOpen = false,
      ).toggleLua,
    )
  }

  @Test
  fun interpolationRestartRestoresLuaWithDisplayPageOnly() {
    val restored = PlayerStatsLuaCommandPolicy.forStartup(1)!!
    assertFalse(restored.toggleLua)
    assertEquals(1, restored.displayPage)
    assertEquals(true, restored.luaVisible)
    val offAfterVanishedOverlay =
      PlayerStatsLuaCommandPolicy.forChip(
        toPage = 0,
        previousPage = 1,
        luaShown = false,
        consoleOpen = false,
      )
    assertFalse(offAfterVanishedOverlay.toggleLua)
    assertNull(offAfterVanishedOverlay.displayPage)
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
  }

  @Test
  fun musicAndComposePagesNeverAskStartupToShowLua() {
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(0))
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(6))
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(7))
  }
}
