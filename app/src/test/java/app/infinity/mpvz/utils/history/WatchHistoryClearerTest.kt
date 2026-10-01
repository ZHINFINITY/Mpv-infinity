package app.infinity.mpvz.utils.history

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryClearerTest {
  @Test
  fun backfillsThenClearsOnlyMediaAndStreamHistory() = runBlocking {
    val events = mutableListOf<String>()
    var mediaHistoryCleared = false
    var streamHistoryCleared = false

    clearWatchHistory(
      backfillWatchStatistics = { events += "backfill" },
      clearMediaHistory = { events += "media"; mediaHistoryCleared = true },
      clearStreamHistory = { events += "stream"; streamHistoryCleared = true },
    )

    assertEquals(listOf("backfill", "media", "stream"), events)
    assertTrue(mediaHistoryCleared)
    assertTrue(streamHistoryCleared)
  }

  @Test
  fun doesNotClearSourceRowsWhenStatisticsBackfillFails() = runBlocking {
    val failure = IllegalStateException("statistics persistence failed")
    var mediaCleared = false
    var streamCleared = false

    val thrown = runCatching {
      clearWatchHistory(
        backfillWatchStatistics = { throw failure },
        clearMediaHistory = { mediaCleared = true },
        clearStreamHistory = { streamCleared = true },
      )
    }.exceptionOrNull()

    assertSame(failure, thrown)
    assertFalse(mediaCleared)
    assertFalse(streamCleared)
  }

  @Test
  fun attemptsBothHistoryStoresWhenOneClearFails() = runBlocking {
    val failure = IllegalStateException("media history clear failed")
    var streamClearAttempted = false

    val thrown = runCatching {
      clearWatchHistory(
        backfillWatchStatistics = {},
        clearMediaHistory = { throw failure },
        clearStreamHistory = { streamClearAttempted = true },
      )
    }.exceptionOrNull()

    assertSame(failure, thrown)
    assertTrue(streamClearAttempted)
  }
}
