package app.infinity.mpvz.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamWatchHistoryTest {
  @Test
  fun disabledHistoryIsNeverReturnedForDisplay() {
    assertEquals(
      emptyList<String>(),
      StreamWatchHistory.keysForDisplay(
        enabled = false,
        order = "source:movie:item:0:0",
        fallbackKeys = setOf("legacy:item:0:0"),
      ),
    )
  }

  @Test
  fun clearedHistoryKeysProduceNoRecentItems() {
    assertEquals(
      emptyList<String>(),
      StreamWatchHistory.keysForDisplay(enabled = true, order = "", fallbackKeys = emptySet()),
    )
  }

  @Test
  fun orderedHistoryIsPreferredWithLegacyKeyFallback() {
    assertEquals(
      listOf("first", "second"),
      StreamWatchHistory.keysForDisplay(
        enabled = true,
        order = "first|second",
        fallbackKeys = setOf("legacy"),
      ),
    )
    assertEquals(
      listOf("legacy"),
      StreamWatchHistory.keysForDisplay(enabled = true, order = "", fallbackKeys = setOf("legacy")),
    )
  }

  @Test
  fun playbackPromotesTheStreamHistoryKeyOnlyWhenEnabled() {
    assertNull(StreamWatchHistory.orderAfterPlayback(enabled = false, currentOrder = "older|current", key = "new"))
    assertEquals(
      listOf("current", "older", "newer"),
      StreamWatchHistory.orderAfterPlayback(enabled = true, currentOrder = "older|current|newer", key = "current"),
    )
  }

  @Test
  fun launchAliasAppliesOnlyToTheMatchingMediaItem() {
    val launchKey = "opaque-launch-stream-key"

    assertEquals(listOf(launchKey), StreamWatchHistory.aliasesForItem(true, launchKey, launchKey))
    assertEquals(emptyList<String>(), StreamWatchHistory.aliasesForItem(true, launchKey, "next-item"))
    assertEquals(emptyList<String>(), StreamWatchHistory.aliasesForItem(false, launchKey, launchKey))
  }

  @Test
  fun fallbackKeysStayInSyncWithTheLimitedOrderedHistory() {
    val ordered = (0..35).map { "opaque-stream-$it" }

    assertEquals(ordered.take(30).toSet(), StreamWatchHistory.fallbackKeysAfterPlayback(ordered))
  }

  @Test
  fun statisticsEntriesUseOrderedKeysThenLegacyFallbackAndStayBounded() {
    assertEquals(
      listOf("first", "second"),
      StreamWatchHistory.statisticsEntriesFor("first|second", setOf("first", "second", "legacy")),
    )
    assertEquals(
      listOf("legacy"),
      StreamWatchHistory.statisticsEntriesFor("", setOf("legacy")),
    )
    val oversized = (0..35).map { "entry-$it" }
    assertEquals(30, StreamWatchHistory.statisticsEntriesFor(oversized.joinToString("|"), oversized.toSet()).size)
  }
}
