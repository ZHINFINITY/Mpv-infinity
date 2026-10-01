package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVolumeKeyPolicyTest {
  @Test
  fun knownAudioUsesSystemUiBeforeAudioOnlyClassificationCatchesUp() {
    assertTrue(
      PlayerVolumeKeyPolicy.shouldUseSystemVolumeUi(
        isAudioOnly = false,
        isKnownAudio = true,
        isAudioLaunch = false,
      ),
    )
  }

  @Test
  fun audioLaunchMarkerUsesSystemUiBeforeAudioOnlyClassificationCatchesUp() {
    assertTrue(
      PlayerVolumeKeyPolicy.shouldUseSystemVolumeUi(
        isAudioOnly = false,
        isKnownAudio = false,
        isAudioLaunch = true,
      ),
    )
  }

  @Test
  fun audioOnlyClassificationStillUsesSystemUi() {
    assertTrue(
      PlayerVolumeKeyPolicy.shouldUseSystemVolumeUi(
        isAudioOnly = true,
        isKnownAudio = false,
        isAudioLaunch = false,
      ),
    )
  }

  @Test
  fun unrecognizedMediaKeepsTheExistingInAppVolumeSliderPath() {
    assertFalse(
      PlayerVolumeKeyPolicy.shouldUseSystemVolumeUi(
        isAudioOnly = false,
        isKnownAudio = false,
        isAudioLaunch = false,
      ),
    )
  }
}
