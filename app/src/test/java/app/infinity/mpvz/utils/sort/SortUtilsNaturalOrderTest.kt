/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.utils.sort

import android.app.Application
import android.net.Uri
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.preferences.SortOrder
import app.infinity.mpvz.preferences.VideoSortType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SortUtilsNaturalOrderTest {
  private val comparator = SortUtils.NaturalOrderComparator.DEFAULT

  @Test
  fun arbitraryLengthNumericRunsHaveTransitiveNaturalOrder() {
    assertTrue(comparator.compare("9", "10") < 0)
    assertTrue(comparator.compare("10", "2147483648") < 0)
    assertTrue(comparator.compare("2147483648", "9") > 0)
    assertTrue(comparator.compare("12", "19") < 0)
    assertTrue(comparator.compare("Episode ٢", "Episode ١٠") < 0)

    assertEquals(
      listOf("9", "10", "2147483648", "999999999999999999999999"),
      listOf("2147483648", "999999999999999999999999", "10", "9").sortedWith(comparator),
    )
  }

  @Test
  fun leadingZeroesRemainEquivalentNumericRuns() {
    assertEquals(0, comparator.compare("Episode 0002", "Episode 2"))
    assertEquals(0, comparator.compare("Part 000", "Part 0"))
  }

  @Test
  fun titleSortVideosHandlesOverflowSizedNumericRuns() {
    val videos =
      listOf(
        video(1, "Clip 2147483648"),
        video(2, "Clip 10"),
        video(3, "Clip 9"),
        video(4, "Episode ١٠"),
        video(5, "Episode ٢"),
      )

    val sorted = SortUtils.sortVideos(videos, VideoSortType.Title, SortOrder.Ascending)

    assertEquals(
      listOf("Clip 9", "Clip 10", "Clip 2147483648", "Episode ٢", "Episode ١٠"),
      sorted.map(Video::displayName),
    )
  }

  private fun video(id: Long, displayName: String) =
    Video(
      id = id,
      title = displayName,
      displayName = displayName,
      path = "/$displayName.mkv",
      uri = Uri.parse("file:///$id.mkv"),
      duration = 1L,
      durationFormatted = "00:00:01",
      size = 1L,
      sizeFormatted = "1 B",
      dateModified = 1L,
      dateAdded = 1L,
      mimeType = "video/x-matroska",
      bucketId = "test",
      bucketDisplayName = "Test",
      width = 1,
      height = 1,
      fps = 24f,
      resolution = "1x1",
    )
}
