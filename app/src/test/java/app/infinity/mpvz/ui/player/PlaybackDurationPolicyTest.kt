/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  @Test
  fun durationSamplesAreRejectedAfterGenerationOrItemChanges() {
    assertTrue(
      PlaybackDurationPolicy.isCurrentSample(
        sampleGeneration = 8L,
        sampleStableId = "stable-item",
        sampleOriginalUri = "content://media/8",
        currentGeneration = 8L,
        currentStableId = "stable-item",
        currentOriginalUri = "content://media/8",
      ),
    )
    assertFalse(
      PlaybackDurationPolicy.isCurrentSample(
        sampleGeneration = 8L,
        sampleStableId = "stable-item",
        sampleOriginalUri = "content://media/8",
        currentGeneration = 9L,
        currentStableId = "next-item",
        currentOriginalUri = "content://media/9",
      ),
    )
    assertFalse(
      PlaybackDurationPolicy.isCurrentSample(
        sampleGeneration = 8L,
        sampleStableId = "stable-item",
        sampleOriginalUri = "content://media/8",
        currentGeneration = 8L,
        currentStableId = "stable-item",
        currentOriginalUri = "content://media/other-uri",
      ),
    )
  }

  @Test
  fun durationSamplesRequireTheLoadedItemToMatchStateAndSelection() {
    assertTrue(
      PlaybackDurationPolicy.isCurrentLoadedItem(
        loadedGeneration = 8L,
        loadedStableId = "stable-item",
        loadedOriginalUri = "content://media/8",
        stateGeneration = 8L,
        stateStableId = "stable-item",
        stateOriginalUri = "content://media/8",
        selectedStableId = "stable-item",
        selectedOriginalUri = "content://media/8",
      ),
    )
    assertFalse(
      PlaybackDurationPolicy.isCurrentLoadedItem(
        loadedGeneration = 8L,
        loadedStableId = "stable-item",
        loadedOriginalUri = "content://media/8",
        stateGeneration = 8L,
        stateStableId = "stable-item",
        stateOriginalUri = "content://media/8",
        selectedStableId = "next-item",
        selectedOriginalUri = "content://media/9",
      ),
    )
    assertFalse(
      PlaybackDurationPolicy.isCurrentLoadedItem(
        loadedGeneration = 8L,
        loadedStableId = "stable-item",
        loadedOriginalUri = "content://media/8",
        stateGeneration = 9L,
        stateStableId = "stable-item",
        stateOriginalUri = "content://media/8",
        selectedStableId = "stable-item",
        selectedOriginalUri = "content://media/8",
      ),
    )
  }

  @Test
  fun durationRefreshReadsFeedTheLatestValueFlow() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val session = File(root, "app/infinity/mpvz/ui/player/PlaybackSession.kt").readText()
    val flow = session.substringAfter("MutableSharedFlow<DurationSample>(").substringBefore(")")
    val refresh = session.substringAfter("private fun publishTimelinePropertiesLocked()").substringBefore("private fun parseEndFileReason(")

    assertTrue(flow.contains("replay = 1"))
    assertTrue(flow.contains("onBufferOverflow = BufferOverflow.DROP_OLDEST"))
    assertTrue(refresh.contains("publishDurationSampleLocked(value)"))
  }

  @Test
  fun audioMiniPlayerProgressReadsTheIntPropertiesTheRestOfThePlayerUses() {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    val miniPlayer = File(root, "app/infinity/mpvz/ui/browser/components/AudioMiniPlayer.kt").readText()
    val session = File(root, "app/infinity/mpvz/ui/player/PlaybackSession.kt").readText()

    // mpv delivers a single format per observed property, so the audio pill asking for Double
    // starved the Int readers (player seek bar, video mini player) and its own fill never moved.
    assertTrue(miniPlayer.contains("propInt[\"duration\"]"))
    assertTrue(miniPlayer.contains("propInt[\"time-pos\"]"))
    assertFalse(miniPlayer.contains("propDouble[\"duration\"]"))
    assertFalse(miniPlayer.contains("propDouble[\"time-pos\"]"))

    // Position is dereferenced in the progress draw lambda only: a tick repaints the fill
    // instead of recomposing the glass surface once a second.
    assertFalse(miniPlayer.contains("val position by PlaybackSession"))
    assertFalse(miniPlayer.contains("val duration by PlaybackSession"))

    // Either delivered numeric format feeds every numeric channel, so no consumer wins the
    // observation and freezes the others.
    assertTrue(session.contains("propDouble.emit(property, value.toDouble())"))
    assertTrue(session.contains("propInt.emit(property, value.toInt())"))
  }
}
