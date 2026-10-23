package app.infinity.mpvz.domain.thumbnail

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull

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

  @Test
  fun malformedHeapAndPressureValuesRemainBounded() {
    for (heap in listOf(-1L, 0L, Long.MAX_VALUE)) {
      for (lowRam in listOf(false, true)) {
        assertTrue(ThumbnailCachePolicy.maxBytes(heap, lowRam) in ThumbnailCachePolicy.MinBytes..ThumbnailCachePolicy.MaxBytes)
      }
    }
    assertEquals(0L, ThumbnailCachePolicy.trimBytes(-1L, false))
  }

  @Test
  fun previewAndFullQualityTiersShareOneHardBudget() {
    for (total in listOf(-1L, 0L, ThumbnailCachePolicy.MinBytes, 32L * 1024 * 1024, ThumbnailCachePolicy.MaxBytes, Long.MAX_VALUE)) {
      val preview = ThumbnailCachePolicy.previewBytes(total)
      val primary = ThumbnailCachePolicy.primaryBytes(total)
      assertEquals(total.coerceIn(ThumbnailCachePolicy.MinBytes, ThumbnailCachePolicy.MaxBytes), primary + preview)
      assertEquals(3L * preview, primary)
      assertTrue(preview > 0L)
    }
  }

  @Test
  fun previewsPreserveAspectWithoutUpscalingOrUnboundedPixelCounts() {
    for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 128 to 128, 40 to 20, Int.MAX_VALUE to 1)) {
      val size = ThumbnailCachePolicy.previewSize(width, height)!!
      assertTrue(size.first in 1..ThumbnailCachePolicy.PreviewMaxEdge)
      assertTrue(size.second in 1..ThumbnailCachePolicy.PreviewMaxEdge)
      assertTrue(size.first <= width && size.second <= height)
    }
    assertEquals(128 to 72, ThumbnailCachePolicy.previewSize(1920, 1080))
    assertEquals(40 to 20, ThumbnailCachePolicy.previewSize(40, 20))
    assertNull(ThumbnailCachePolicy.previewSize(0, 100))
    assertNull(ThumbnailCachePolicy.previewSize(100, -1))
  }

  @Test
  fun identitySurvivesViewportRecreationButInvalidatesChangedMedia() {
    val original = ThumbnailCachePolicy.sourceIdentity("/movie.mkv", "content://movie", 100L, 200L, 300L)
    assertEquals(original, ThumbnailCachePolicy.sourceIdentity("/movie.mkv", "content://movie", 100L, 200L, 300L))
    assertTrue(original != ThumbnailCachePolicy.sourceIdentity("/movie.mkv", "content://movie", 100L, 201L, 300L))
    assertEquals("content://movie|100|200|300", ThumbnailCachePolicy.sourceIdentity("", "content://movie", 100L, 200L, 300L))
  }
}
