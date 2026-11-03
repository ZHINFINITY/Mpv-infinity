package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSelectionUtilsTest {
  @Test fun firstTapSelectsPrimaryAndSecondTapSelectsSecondary() {
    val primary = toggleMpvSubtitleSelection(0, 0, 4)
    assertEquals(MpvSubtitleSelectionIds(4, 0), primary)
    assertEquals(MpvSubtitleSelectionIds(4, 6), toggleMpvSubtitleSelection(primary.primaryId, primary.secondaryId, 6))
  }

  @Test fun togglingEitherSelectedTrackOffPreservesTheOtherTrack() {
    assertEquals(MpvSubtitleSelectionIds(4, 0), toggleMpvSubtitleSelection(4, 6, 6))
    assertEquals(MpvSubtitleSelectionIds(0, 6), toggleMpvSubtitleSelection(4, 6, 4))
  }

  @Test fun thirdTrackReplacesPrimaryAndInvalidIdsDoNothing() {
    assertEquals(MpvSubtitleSelectionIds(8, 6), toggleMpvSubtitleSelection(4, 6, 8))
    assertEquals(MpvSubtitleSelectionIds(4, 6), toggleMpvSubtitleSelection(4, 6, 0))
  }
}
