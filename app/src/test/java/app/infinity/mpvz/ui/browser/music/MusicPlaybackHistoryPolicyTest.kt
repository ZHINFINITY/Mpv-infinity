package app.infinity.mpvz.ui.browser.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPlaybackHistoryPolicyTest {
  @Test
  fun recordsOnlyActualPlayStartsAndMediaTransitions() {
    val gate = MusicPlaybackHistoryGate()

    assertNull(gate.onMediaItemTransition("music:1", isPlaying = false))
    assertEquals("music:1", gate.onIsPlayingChanged("music:1", isPlaying = true))
    assertNull(gate.onIsPlayingChanged("music:1", isPlaying = true))

    assertNull(gate.onIsPlayingChanged("music:1", isPlaying = false))
    assertEquals("music:1", gate.onIsPlayingChanged("music:1", isPlaying = true))

    assertEquals("music:2", gate.onMediaItemTransition("music:2", isPlaying = true))
    assertNull(gate.onIsPlayingChanged("music:2", isPlaying = true))

    assertNull(gate.onMediaItemTransition(null, isPlaying = false))
    assertNull(gate.onIsPlayingChanged(null, isPlaying = true))
  }
}
