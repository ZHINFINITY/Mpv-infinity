package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsOverlayPolicyTest {
  private fun input(engine: PlayerStatsOverlayPolicy.Engine, enabled: Boolean = true, page: Int = 1,
    audio: Boolean = false, music: Boolean = false, known: Boolean = true) =
    PlayerStatsOverlayPolicy.Input(enabled, page, engine, audio, music, known)

  @Test fun nativeToMpvAndMpvToNativeReapplySamePage() {
    assertEquals(PlayerStatsOverlayPolicy.Action.SHOW_PAGE,
      PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV)).action)
    assertEquals(PlayerStatsOverlayPolicy.Action.SHOW_PAGE,
      PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.NATIVE)).action)
  }

  @Test fun coreReplacementAndFileLoadUseSameIdempotentDecision() {
    val before = PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV))
    val afterCoreReplacement = PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV))
    val afterFileLoad = PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV))
    assertEquals(before, afterCoreReplacement)
    assertEquals(before, afterFileLoad)
  }

  @Test fun offHidesBothEnginesAndRepeatedOffDoesNotToggle() {
    PlayerStatsOverlayPolicy.Engine.entries.forEach { engine ->
      val off = PlayerStatsOverlayPolicy.decide(input(engine, enabled = false))
      assertEquals(PlayerStatsOverlayPolicy.Action.HIDE, off.action)
      assertEquals(off, PlayerStatsOverlayPolicy.decide(input(engine, enabled = false)))
    }
  }

  @Test fun audioMusicAndUnknownFirstFramesNeverShow() {
    listOf(
      input(PlayerStatsOverlayPolicy.Engine.MPV, audio = true),
      input(PlayerStatsOverlayPolicy.Engine.NATIVE, music = true),
      input(PlayerStatsOverlayPolicy.Engine.NATIVE, known = false),
    ).forEach { assertFalse(PlayerStatsOverlayPolicy.decide(it).visible) }
    assertTrue(PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.NATIVE)).visible)
  }

  @Test fun sheetOpenHidesComposeStatsEvenWhenAPageIsEnabled() {
    val hidden = PlayerStatsOverlayPolicy.decide(
      input(PlayerStatsOverlayPolicy.Engine.MPV).copy(sheetOrPanelOpen = true),
    )
    assertFalse(hidden.visible)
  }
}
