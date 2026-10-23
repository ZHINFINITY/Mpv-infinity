package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackSelectionFingerprintTest {
  private val nativeEnglish = TrackSelectionMetadata("eng", "English", "aac", "audio/aac", 2)
  private val mpvEnglish = TrackSelectionMetadata("en", "English", "mp4a.40.2", null, 2)

  @Test fun disabledIsExplicitAndNeverRemapped() {
    assertEquals(-1, TrackSelectionMatcher.findMatchIndex(SharedTrackSelection.Disabled, listOf(nativeEnglish)))
  }

  @Test fun unknownDoesNotGuess() {
    assertNull(TrackSelectionMatcher.findMatchIndex(SharedTrackSelection.Unknown, listOf(nativeEnglish)))
  }

  @Test fun nativeFingerprintMatchesMpvLanguageCodecAndMimeDifferences() {
    val fingerprint = TrackSelectionFingerprint.capture(nativeEnglish, TrackSelectionState.SELECTED)!!
    assertEquals(0, TrackSelectionMatcher.findMatchIndex(SharedTrackSelection.selected(fingerprint), listOf(mpvEnglish)))
  }

  @Test fun mpvFingerprintMatchesNativeLanguageCodecAndMimeDifferences() {
    val fingerprint = TrackSelectionFingerprint.capture(mpvEnglish, TrackSelectionState.SELECTED)!!
    assertEquals(0, TrackSelectionMatcher.findMatchIndex(SharedTrackSelection.selected(fingerprint), listOf(nativeEnglish)))
  }

  @Test fun subtitleCodecAliasesMatchAcrossEngines() {
    val native = TrackSelectionMetadata("eng", "English", "srt", "application/x-subrip")
    val mpv = TrackSelectionMetadata("en", "English", "subrip", "text/srt")
    val fingerprint = TrackSelectionFingerprint.capture(native, TrackSelectionState.SELECTED)!!
    assertEquals(0, TrackSelectionMatcher.findMatchIndex(SharedTrackSelection.selected(fingerprint), listOf(mpv)))
  }

  @Test fun missingCandidateMetadataDoesNotContradictOrPreventPositiveMatch() {
    val fingerprint = TrackSelectionFingerprint.capture(nativeEnglish, TrackSelectionState.SELECTED)!!
    assertEquals(0, TrackSelectionMatcher.findMatchIndex(
      SharedTrackSelection.selected(fingerprint), listOf(TrackSelectionMetadata(language = "en", label = null)),
    ))
  }

  @Test fun contradictoryLanguageIsRejectedWhenBothSidesHaveIt() {
    val fingerprint = TrackSelectionFingerprint.capture(nativeEnglish, TrackSelectionState.SELECTED)!!
    assertNull(TrackSelectionMatcher.findMatchIndex(
      SharedTrackSelection.selected(fingerprint), listOf(mpvEnglish.copy(language = "de")),
    ))
  }

  @Test fun externalTrackNeverFallsBackToEmbeddedTrack() {
    val external = nativeEnglish.copy(externalUri = "https://example.test/sub.srt")
    val fingerprint = TrackSelectionFingerprint.capture(external, TrackSelectionState.SELECTED)!!
    assertNull(TrackSelectionMatcher.findMatchIndex(
      SharedTrackSelection.selected(fingerprint), listOf(nativeEnglish, external.copy(label = "Deutsch"))),
    )
  }

  @Test fun ambiguousDescriptorsNeedMatchingOrdinal() {
    val fingerprint = TrackSelectionFingerprint.capture(nativeEnglish, TrackSelectionState.SELECTED, ordinal = 1)!!
    val candidates = listOf(nativeEnglish, nativeEnglish)
    assertEquals(1, TrackSelectionMatcher.findMatchIndex(SharedTrackSelection.selected(fingerprint), candidates))
  }

  @Test fun ambiguousDescriptorsWithoutOrdinalAreRejected() {
    val fingerprint = TrackSelectionFingerprint.capture(nativeEnglish, TrackSelectionState.SELECTED)!!
    assertNull(TrackSelectionMatcher.findMatchIndex(SharedTrackSelection.selected(fingerprint), listOf(nativeEnglish, nativeEnglish)))
  }
}
