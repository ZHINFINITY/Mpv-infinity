package app.infinity.mpvz.utils.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentlyPlayedVisibilityTest {
  @Test
  fun disabledGlobalToggleHidesStoredItemsWithoutMutatingThem() {
    val storedItems = listOf("first", "second")

    assertEquals(emptyList<String>(), visibleRecentlyPlayedItems(enabled = false, items = storedItems))
    assertEquals(storedItems, visibleRecentlyPlayedItems(enabled = true, items = storedItems))
    assertEquals(listOf("first", "second"), storedItems)
  }

  @Test
  fun statisticsRequireBothOptInPreferences() {
    assertFalse(shouldTrackWatchStatistics(recentlyPlayedEnabled = false, watchStatisticsEnabled = true))
    assertFalse(shouldTrackWatchStatistics(recentlyPlayedEnabled = true, watchStatisticsEnabled = false))
    assertTrue(shouldTrackWatchStatistics(recentlyPlayedEnabled = true, watchStatisticsEnabled = true))
  }
}
