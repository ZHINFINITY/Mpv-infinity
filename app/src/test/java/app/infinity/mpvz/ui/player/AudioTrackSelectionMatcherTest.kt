package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackSelectionMatcherTest {
  @Test
  fun mapsEnglishDtsHdToEnglishInsteadOfFirstJapaneseDtsTrack() {
    val target = AudioTrackFingerprint(
      language = "en-US",
      label = "English DTS-HD MA 5.1",
      codec = "dtsl",
      mimeType = "audio/vnd.dts.hd",
      channelCount = 6,
    )
    val candidates = listOf(
      AudioTrackFingerprint("jpn", "Japanese DTS-HD MA 5.1", "dts", channelCount = 6),
      AudioTrackFingerprint("eng", "English DTS-HD MA 5.1", "dts", channelCount = 6),
      AudioTrackFingerprint("cmn", "Mandarin DTS", "dts", channelCount = 6),
      AudioTrackFingerprint("eng", "English AC3 5.1", "ac3", channelCount = 6),
    )

    assertEquals(1, AudioTrackSelectionMatcher.findBestMatchIndex(target, candidates))
  }

  @Test
  fun languageAliasesMatchAcrossMedia3AndMpv() {
    val target = AudioTrackFingerprint(language = "ja-JP", label = "Japanese DTS-HD", mimeType = "audio/vnd.dts.hd")
    val candidates = listOf(
      AudioTrackFingerprint("eng", "English DTS-HD", "dts", channelCount = 6),
      AudioTrackFingerprint("jpn", "Japanese DTS-HD", "dts", channelCount = 6),
    )

    assertEquals(1, AudioTrackSelectionMatcher.findBestMatchIndex(target, candidates))
  }

  @Test
  fun ambiguousOrConflictingCandidatesAreNotSelected() {
    val target = AudioTrackFingerprint(language = "eng", codec = "dts", channelCount = 6)
    val ambiguous = listOf(
      AudioTrackFingerprint("en", "Track A", "dts", channelCount = 6),
      AudioTrackFingerprint("eng", "Track B", "dca", channelCount = 6),
    )
    assertNull(AudioTrackSelectionMatcher.findBestMatchIndex(target, ambiguous))

    val conflicting = listOf(
      AudioTrackFingerprint("jpn", "Japanese DTS-HD", "dts", channelCount = 6),
      AudioTrackFingerprint("eng", "English AC3", "ac3", channelCount = 6),
    )
    assertNull(AudioTrackSelectionMatcher.findBestMatchIndex(target, conflicting))
  }
}
