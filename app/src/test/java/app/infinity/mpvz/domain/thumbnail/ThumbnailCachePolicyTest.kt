package app.infinity.mpvz.domain.thumbnail

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ThumbnailCachePolicyTest {
  @Test
  fun cacheBudgetIsBoundedAndLowRamIsSmaller() {
    assertEquals(
      ThumbnailCachePolicy.MaxBytes,
      ThumbnailCachePolicy.maxBytes(512L * 1024L * 1024L, lowRam = false),
    )
    assertEquals(
      ThumbnailCachePolicy.MinBytes,
      ThumbnailCachePolicy.maxBytes(32L * 1024L * 1024L, lowRam = true),
    )
    assertTrue(
      ThumbnailCachePolicy.maxBytes(256L * 1024L * 1024L, lowRam = true) <
        ThumbnailCachePolicy.maxBytes(256L * 1024L * 1024L, lowRam = false),
    )
  }

  @Test
  fun pressureTrimIsBoundedAndCriticalPressureEvictsAll() {
    assertEquals(0L, ThumbnailCachePolicy.trimBytes(32L * 1024L * 1024L, runningCritical = true))
    assertEquals(
      16L * 1024L * 1024L,
      ThumbnailCachePolicy.trimBytes(32L * 1024L * 1024L, runningCritical = false),
    )
  }
}
