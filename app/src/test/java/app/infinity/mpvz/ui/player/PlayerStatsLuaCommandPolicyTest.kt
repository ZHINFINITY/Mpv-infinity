/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsLuaCommandPolicyTest {
  @Test
  fun offHidesLuaAndClearsOsdWithoutWaitingForVisibleProperty() {
    val decision = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 0,
      previousPage = 1,
      luaShown = false,
      consoleOpen = false,
    )
    assertTrue(decision.toggleLua)
    assertTrue(decision.clearOsd)
    assertEquals(false, decision.luaVisible)
    assertNull(decision.displayPage)
  }

  @Test
  fun offClearsLeftoverOsdWhenLuaVisibleFlagIsStale() {
    val decision = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 0,
      previousPage = 3,
      luaShown = true,
      consoleOpen = true,
    )
    assertTrue(decision.toggleLua)
    assertTrue(decision.clearOsd)
    assertTrue(decision.disableConsole)
    assertEquals(false, decision.luaVisible)
  }

  @Test
  fun offDoesNotInvertHiddenLuaWhenAlreadyOff() {
    val decision = PlayerStatsLuaCommandPolicy.forChip(
      toPage = 0,
      previousPage = 0,
      luaShown = false,
      consoleOpen = false,
    )
    assertFalse(decision.toggleLua)
    assertTrue(decision.clearOsd)
  }

  @Test
  fun startupRestoreDoesNotExtraToggle() {
    val decision = PlayerStatsLuaCommandPolicy.forStartup(2)!!
    assertFalse(decision.toggleLua)
    assertEquals(2, decision.displayPage)
    assertEquals(true, decision.luaVisible)
    assertNull(PlayerStatsLuaCommandPolicy.forStartup(0))
  }
}
