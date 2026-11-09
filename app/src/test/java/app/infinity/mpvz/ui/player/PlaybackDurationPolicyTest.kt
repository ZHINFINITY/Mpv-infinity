/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDurationPolicyTest {
  @Test
  fun queueMetadataSeedsTheSameTrackBaselineBeforeResumedMpvDurationArrives() {
    val seededDuration = PlaybackDurationPolicy.initialDurationSeconds(180)
    val resumedDuration =
      PlaybackDurationPolicy.resolve(
        previousDurationSeconds = seededDuration,
        observedDurationSeconds = 90.0,
        sameItem = true,
        preserveSameItemDuration = true,
      )

    assertEquals(180.0, seededDuration, 0.0)
    assertEquals(180.0, resumedDuration, 0.0)
  }

  @Test
  fun backgroundResumeKeepsKnownSameTrackDurationWhenMpvReportsStaleShortDuration() {
    val resolved =
      PlaybackDurationPolicy.resolve(
        previousDurationSeconds = 180.0,
        observedDurationSeconds = 90.0,
        sameItem = true,
        preserveSameItemDuration = true,
      )

    assertEquals(180.0, resolved, 0.0)
  }

  @Test
  fun nextTrackUsesItsOwnDurationInsteadOfCarryingPreviousTrackDuration() {
    val afterBackgroundResume =
      PlaybackDurationPolicy.resolve(
        previousDurationSeconds = 180.0,
        observedDurationSeconds = 90.0,
        sameItem = true,
        preserveSameItemDuration = true,
      )
    val nextTrack =
      PlaybackDurationPolicy.resolve(
        previousDurationSeconds = afterBackgroundResume,
        observedDurationSeconds = 245.0,
        sameItem = false,
        preserveSameItemDuration = true,
      )

    assertEquals(245.0, nextTrack, 0.0)
  }

  @Test
  fun missingOrInvalidDurationDoesNotReplaceKnownSameTrackDuration() {
    val resolved =
      PlaybackDurationPolicy.resolve(
        previousDurationSeconds = 180.0,
        observedDurationSeconds = Double.NaN,
        sameItem = true,
        preserveSameItemDuration = true,
      )

    assertEquals(180.0, resolved, 0.0)
  }

  @Test
  fun serviceResynchronizesDurationAfterTheCurrentGenerationFinishesLoading() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val service = File(root, "app/infinity/mpvz/ui/player/MediaPlaybackService.kt").readText()
    val readySync = service.substringAfter("PlaybackSession.state\n        .map { it.generation to it.phase }")
      .substringBefore("serviceScope.launch")

    assertTrue(readySync.contains(".distinctUntilChanged()"))
    assertTrue(readySync.contains("phase != PlaybackPhase.READY && phase != PlaybackPhase.BACKGROUND"))
    assertTrue(readySync.contains("PlaybackSession.state.value.generation == generation"))
    assertTrue(readySync.contains("syncMediaDurationForCurrentItem()"))
  }
}
