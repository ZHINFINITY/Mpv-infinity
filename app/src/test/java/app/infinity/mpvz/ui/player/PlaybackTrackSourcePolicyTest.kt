/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTrackSourcePolicyTest {
  @Test
  fun tracksFollowTheActiveEngineAcrossSwitchesInBothDirections() {
    val mpvTracks = listOf("MPV audio", "MPV subtitles")
    val nativeTracks = listOf("Native audio", "Native subtitles")

    assertEquals(
      mpvTracks,
      PlaybackTrackSourcePolicy.forActiveEngine(PlaybackEngineMode.MPV, mpvTracks, nativeTracks),
    )
    assertEquals(
      nativeTracks,
      PlaybackTrackSourcePolicy.forActiveEngine(PlaybackEngineMode.NATIVE, mpvTracks, nativeTracks),
    )
    assertEquals(
      mpvTracks,
      PlaybackTrackSourcePolicy.forActiveEngine(PlaybackEngineMode.MPV, mpvTracks, nativeTracks),
    )
    assertEquals(
      nativeTracks,
      PlaybackTrackSourcePolicy.forActiveEngine(PlaybackEngineMode.NATIVE, mpvTracks, nativeTracks),
    )
  }
}
