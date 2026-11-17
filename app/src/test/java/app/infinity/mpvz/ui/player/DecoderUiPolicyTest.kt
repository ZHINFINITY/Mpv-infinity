package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderUiPolicyTest {
  @Test fun qualityMappingHasTenStableEndpoints() {
    assertEquals(0, DecoderUiPolicy.profileForQuality(0))
    assertEquals(9, DecoderUiPolicy.profileForQuality(100))
    assertEquals(0, DecoderUiPolicy.qualityForProfile(0))
    assertEquals(100, DecoderUiPolicy.qualityForProfile(9))
    for (profile in 0 until DecoderUiPolicy.PROFILE_COUNT) {
      assertEquals(profile, DecoderUiPolicy.profileForQuality(DecoderUiPolicy.qualityForProfile(profile)))
    }
  }

  @Test fun playerTabsNeverExposeGlobalAuto() {
    assertEquals(listOf(PlaybackEngineMode.MPV, PlaybackEngineMode.NATIVE), DecoderUiPolicy.playerEngineTabs())
  }
}
