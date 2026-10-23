package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionIdentityTest {
  @Test
  fun sameStableIdWithDifferentUriIsANewMediaGeneration() {
    val tracker = MediaSessionIdentityTracker()
    val first = MediaSessionItemIdentity("station", "https://one/stream")
    val second = MediaSessionItemIdentity("station", "https://two/stream")

    val firstGeneration = tracker.advance(first)
    val secondGeneration = tracker.advance(second)

    assertNotEquals(firstGeneration, secondGeneration)
    assertFalse(tracker.isCurrent(first, firstGeneration))
    assertTrue(tracker.isCurrent(second, secondGeneration))
  }

  @Test
  fun rapidSkipInvalidatesEveryOlderArtworkCompletion() {
    val tracker = MediaSessionIdentityTracker()
    val a = MediaSessionItemIdentity("a", "file:///a")
    val b = MediaSessionItemIdentity("b", "file:///b")
    val c = MediaSessionItemIdentity("c", "file:///c")

    val aGeneration = tracker.advance(a)
    val bGeneration = tracker.advance(b)
    val cGeneration = tracker.advance(c)

    assertFalse(tracker.isCurrent(a, aGeneration))
    assertFalse(tracker.isCurrent(b, bGeneration))
    assertTrue(tracker.isCurrent(c, cGeneration))
  }

  @Test
  fun unchangedQueueItemDoesNotInvalidateItsInFlightCompletion() {
    val tracker = MediaSessionIdentityTracker()
    val identity = MediaSessionItemIdentity("same", "https://stream")
    val firstGeneration = tracker.advance(identity)
    val secondGeneration = tracker.advance(identity)

    assertTrue(firstGeneration == secondGeneration)
    assertTrue(tracker.isCurrent(identity, firstGeneration))
  }

  @Test
  fun returningToAnEarlierSongDoesNotReviveItsOldAsyncCompletion() {
    val tracker = MediaSessionIdentityTracker()
    val a = MediaSessionItemIdentity("a", "file:///a")
    val oldA = tracker.advance(a)
    tracker.advance(MediaSessionItemIdentity("b", "file:///b"))
    val newA = tracker.advance(a)
    assertFalse(tracker.isCurrent(a, oldA))
    assertTrue(tracker.isCurrent(a, newA))
  }

  @Test
  fun metadataMustMatchBothTheQueueItemAndItsOriginalSource() {
    val current = MediaSessionItemIdentity("same-id", "file:///new-song")
    assertFalse(current.acceptsMetadata("same-id", "file:///old-song"))
    assertFalse(current.acceptsMetadata("old-id", "file:///new-song"))
    assertTrue(current.acceptsMetadata("same-id", "file:///new-song"))
  }

  @Test
  fun artworkCacheKeysChangeWithSourceCoverOrQueuePosition() {
    val first = MediaSessionItemIdentity("same-id", "file:///first")
    val second = MediaSessionItemIdentity("same-id", "file:///second")
    assertNotEquals(first.artworkKey(0, "cover"), second.artworkKey(0, "cover"))
    assertNotEquals(first.artworkKey(0, "cover"), first.artworkKey(0, "new-cover"))
    assertNotEquals(first.artworkKey(0, "cover"), first.artworkKey(1, "cover"))
  }

  @Test
  fun artworkKeysDoNotCollideWhenSourceStringsContainSeparators() {
    val first = MediaSessionItemIdentity("a|b", "c")
    val second = MediaSessionItemIdentity("a", "b|c")
    assertNotEquals(first.artworkKey(0, null), second.artworkKey(0, null))
  }
}
