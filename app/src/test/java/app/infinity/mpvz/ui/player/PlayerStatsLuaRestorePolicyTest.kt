package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsLuaRestorePolicyTest {
  @Test
  fun interpolationRestoreWaitsForLoadedPlaybackAndFinitePosition() {
    assertFalse(PlayerStatsLuaRestorePolicy.shouldWaitForPlaybackBeforeRestore(fileLoaded = false, timePosFinite = true))
    assertFalse(PlayerStatsLuaRestorePolicy.shouldWaitForPlaybackBeforeRestore(fileLoaded = true, timePosFinite = false))
    assertTrue(PlayerStatsLuaRestorePolicy.shouldWaitForPlaybackBeforeRestore(fileLoaded = true, timePosFinite = true))
  }

  @Test
  fun voReconfigReappliesOnlyAfterRestoreAndOnlyOnce() {
    assertTrue(PlayerStatsLuaRestorePolicy.shouldReapplyAfterVoReconfig(luaRestored = true, alreadyReapplied = false))
    assertFalse(PlayerStatsLuaRestorePolicy.shouldReapplyAfterVoReconfig(luaRestored = true, alreadyReapplied = true))
    assertFalse(PlayerStatsLuaRestorePolicy.shouldReapplyAfterVoReconfig(luaRestored = false, alreadyReapplied = false))
  }
}
