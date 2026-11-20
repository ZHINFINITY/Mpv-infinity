/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.browser.videolist

import android.app.Application
import android.net.Uri
import app.infinity.mpvz.domain.media.model.Video
import app.infinity.mpvz.utils.history.RecentlyPlayedOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ManualVideoWatchedOverrideTest {
  private val context by lazy { RuntimeEnvironment.getApplication() }

  @Test
  fun explicitUnwatchedOverrideKeepsAnOldVideoNewAcrossBrowserSurfaces() {
    val video = video(path = "/storage/movies/old.mkv", dateModified = 1L)
    setExplicitVideoWatchedOverride(context, video.path, watched = false)

    val override = explicitVideoWatchedOverride(context, video.path)
    val status =
      buildVideoWithPlaybackInfo(
        video = video,
        playbackState = null,
        currentTimeMillis = 1_000_000_000L,
        newLabelDays = 7,
        watchedThreshold = 95,
        explicitlyMarkedUnwatched = override == false,
        explicitlyMarkedWatched = override == true,
      )

    assertEquals(false, override)
    assertTrue(status.isOldAndUnplayed)
    assertFalse(status.isWatched)
  }

  @Test
  fun recentlyPlayedResolverMatchesStoredPathForFileUrisAndPreservesWebUris() {
    assertEquals(
      "/storage/movies/movie.mkv",
      RecentlyPlayedOps.resolveRecentlyPlayedFilePath(
        context,
        Uri.parse("file:///storage/movies/movie.mkv"),
      ),
    )
    assertEquals(
      "https://cdn.example/movie.mkv",
      RecentlyPlayedOps.resolveRecentlyPlayedFilePath(
        context,
        Uri.parse("https://cdn.example/movie.mkv"),
      ),
    )
  }

  @Test
  fun explicitWatchedOverrideSuppressesANewLabel() {
    val video = video(path = "/storage/movies/recent.mkv", dateModified = 999_999L)
    setExplicitVideoWatchedOverride(context, video.path, watched = true)

    val override = explicitVideoWatchedOverride(context, video.path)
    val status =
      buildVideoWithPlaybackInfo(
        video = video,
        playbackState = null,
        currentTimeMillis = 1_000_000_000L,
        newLabelDays = 7,
        watchedThreshold = 95,
        explicitlyMarkedUnwatched = override == false,
        explicitlyMarkedWatched = override == true,
      )

    assertEquals(true, override)
    assertTrue(status.isWatched)
    assertFalse(status.isOldAndUnplayed)
  }

  private fun video(path: String, dateModified: Long) =
    Video(
      id = path.hashCode().toLong(),
      title = path.substringAfterLast('/'),
      displayName = path.substringAfterLast('/'),
      path = path,
      uri = Uri.parse("file://$path"),
      duration = 600_000L,
      durationFormatted = "00:10:00",
      size = 1L,
      sizeFormatted = "1 B",
      dateModified = dateModified,
      dateAdded = dateModified,
      mimeType = "video/x-matroska",
      bucketId = "/storage/movies",
      bucketDisplayName = "Movies",
      width = 1920,
      height = 1080,
      fps = 24f,
      resolution = "1920x1080",
    )
}
