package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderUiPolicyTest {
  @Test fun qualityMappingHasTenStableEndpoints() {
    assertEquals(0, DecoderUiPolicy.profileForQuality(0))
    assertEquals(9, DecoderUiPolicy.profileForQuality(100))
    assertEquals(0, DecoderUiPolicy.qualityForProfile(0))
    assertEquals(100, DecoderUiPolicy.qualityForProfile(9))
    assertEquals(10, DecoderUiPolicy.qualityForProfile(1))
    assertEquals(90, DecoderUiPolicy.qualityForProfile(8))
  }

  @Test fun playerTabsNeverExposeGlobalAuto() {
    assertEquals(listOf(PlaybackEngineMode.MPV, PlaybackEngineMode.NATIVE), DecoderUiPolicy.playerEngineTabs())
  }
}
