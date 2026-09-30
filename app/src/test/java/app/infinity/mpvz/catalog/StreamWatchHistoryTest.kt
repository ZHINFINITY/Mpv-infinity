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
}
