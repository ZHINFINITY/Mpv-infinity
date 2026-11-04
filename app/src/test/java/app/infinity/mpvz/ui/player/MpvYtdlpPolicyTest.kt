package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvYtdlpPolicyTest {
  @Test
  fun knownAudioStreamBypassesPageResolverAndMpvHook() {
    assertFalse(
      MpvYtdlpPolicy.requiresPageResolution(
        looksLikeWebSource = true,
        isDefinitelyAudioOnly = true,
        detectedAudioMimeType = null,
      ),
    )
    assertTrue(
      MpvYtdlpPolicy.shouldDisableHook(
        isDefinitelyAudioOnly = true,
        detectedAudioMimeType = null,
      ),
    )
  }

  @Test
  fun ambiguousWebPageStillUsesYtdlp() {
    assertTrue(
      MpvYtdlpPolicy.requiresPageResolution(
        looksLikeWebSource = true,
        isDefinitelyAudioOnly = false,
        detectedAudioMimeType = null,
      ),
    )
    assertFalse(
      MpvYtdlpPolicy.shouldDisableHook(
        isDefinitelyAudioOnly = false,
        detectedAudioMimeType = null,
      ),
    )
  }

  @Test
  fun probedRadioMimeBypassesPageResolverAndMpvHook() {
    assertFalse(
      MpvYtdlpPolicy.requiresPageResolution(
        looksLikeWebSource = true,
        isDefinitelyAudioOnly = false,
        detectedAudioMimeType = "audio/mpeg",
      ),
    )
    assertTrue(
      MpvYtdlpPolicy.shouldDisableHook(
        isDefinitelyAudioOnly = false,
        detectedAudioMimeType = "audio/mpeg",
      ),
    )
  }

  @Test
  fun nonWebUnknownSourceDoesNotRequestPageResolution() {
    assertFalse(
      MpvYtdlpPolicy.requiresPageResolution(
        looksLikeWebSource = false,
        isDefinitelyAudioOnly = false,
        detectedAudioMimeType = null,
      ),
    )
  }
}
