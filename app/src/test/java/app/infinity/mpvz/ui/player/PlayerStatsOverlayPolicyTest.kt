package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsOverlayPolicyTest {
  private fun input(engine: PlayerStatsOverlayPolicy.Engine, enabled: Boolean = true, page: Int = 1,
    audio: Boolean = false, music: Boolean = false, known: Boolean = true) =
    PlayerStatsOverlayPolicy.Input(enabled, page, engine, audio, music, known)

  @Test fun mpvShowsComposeOnlyOnPageSixNativeShowsOneThroughSix() {
    assertEquals(PlayerStatsOverlayPolicy.Action.HIDE,
      PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV, page = 1)).action)
    assertEquals(PlayerStatsOverlayPolicy.Action.SHOW_PAGE,
      PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV, page = 6)).action)
    assertEquals(PlayerStatsOverlayPolicy.Action.SHOW_PAGE,
      PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.NATIVE, page = 1)).action)
    assertEquals(PlayerStatsOverlayPolicy.Action.SHOW_PAGE,
      PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.NATIVE, page = 6)).action)
  }

  @Test fun coreReplacementAndFileLoadUseSameIdempotentDecision() {
    val before = PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV, page = 6))
    val afterCoreReplacement = PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV, page = 6))
    val afterFileLoad = PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.MPV, page = 6))
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
      input(PlayerStatsOverlayPolicy.Engine.MPV, page = 6, audio = true),
      input(PlayerStatsOverlayPolicy.Engine.NATIVE, music = true),
      input(PlayerStatsOverlayPolicy.Engine.NATIVE, known = false),
    ).forEach { assertFalse(PlayerStatsOverlayPolicy.decide(it).visible) }
    assertTrue(PlayerStatsOverlayPolicy.decide(input(PlayerStatsOverlayPolicy.Engine.NATIVE)).visible)
  }

  @Test fun moreSheetDoesNotHidePageSixComposeOverlay() {
    val shown = PlayerStatsOverlayPolicy.decide(
      input(PlayerStatsOverlayPolicy.Engine.MPV, page = 6),
    )
    assertTrue(shown.visible)
  }
}
