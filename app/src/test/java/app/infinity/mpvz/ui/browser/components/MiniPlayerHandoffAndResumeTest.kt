/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.components

import app.infinity.mpvz.ui.player.AudioPlaybackResumeSnapshot
import app.infinity.mpvz.ui.player.AudioPlaybackResumeStore
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlaybackPhase
import app.infinity.mpvz.ui.player.PlaybackQueueState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner reports on build 1907:
 *  1. First minimize "vanishes then turns into the mini player": the real mini player was hidden
 *     during a PlayerActivity-hosted morph because the service only reports foreground after
 *     PlayerActivity leaves, then it slid in afterwards.
 *  2. X on the mini player showed "a second mini player only for that song": the resume card
 *     was offered right after the user closed the session.
 *  3. Resuming "only plays that song, it doesn't know the library": library queues over 100
 *     items were persisted as the current song only.
 */
class MiniPlayerHandoffAndResumeTest {
  private fun snapshot(closedByUser: Boolean) =
    AudioPlaybackResumeSnapshot(
      queueState = PlaybackQueueState(items = listOf(PlaybackItem(stableId = "a", originalUri = "file:///a.mp3")), currentIndex = 0),
      positionSeconds = 12.0,
      paused = true,
      closedByUser = closedByUser,
    )

  @Test
  fun realMiniPlayerShowsDuringAPlayerActivityMinimizeWithoutEnterAnimation() {
    var phase = ActivityMinimizePhase.Idle
    // PlayerActivity in front: service is not foreground, so before the fix nothing showed.
    assertFalse(ActivityMinimizePolicy.showsMiniPlayer(isServiceRunning = false, phase = phase, isAudioOnlyItem = true))

    phase = ActivityMinimizePolicy.onProgress(phase, minimizing = true)
    assertEquals(ActivityMinimizePhase.Morphing, phase)
    assertTrue(ActivityMinimizePolicy.showsMiniPlayer(isServiceRunning = false, phase = phase, isAudioOnlyItem = true))
    assertTrue("the morph is the enter animation", ActivityMinimizePolicy.skipsEnterAnimation(false, phase))

    // Landed and PlayerActivity finished, the service has not reported foreground yet: still shown.
    phase = ActivityMinimizePolicy.onPageDisposed(phase)
    assertEquals(ActivityMinimizePhase.Landed, phase)
    assertTrue(ActivityMinimizePolicy.showsMiniPlayer(isServiceRunning = false, phase = phase, isAudioOnlyItem = true))
    assertTrue(ActivityMinimizePolicy.skipsEnterAnimation(false, phase))
  }

  @Test
  fun growingBackToFullInsidePlayerActivityHidesTheMiniPlayerAgain() {
    val morphing = ActivityMinimizePolicy.onProgress(ActivityMinimizePhase.Idle, minimizing = true)
    val back = ActivityMinimizePolicy.onProgress(morphing, minimizing = false)
    assertEquals(ActivityMinimizePhase.Idle, back)
    assertFalse(ActivityMinimizePolicy.showsMiniPlayer(isServiceRunning = false, phase = back, isAudioOnlyItem = true))
    assertFalse(ActivityMinimizePolicy.skipsEnterAnimation(false, back))
    // A page disposed while idle (normal close, video) never leaves a phantom mini player.
    assertEquals(ActivityMinimizePhase.Idle, ActivityMinimizePolicy.onPageDisposed(ActivityMinimizePhase.Idle))
  }

  @Test
  fun activityMinimizeNeverShowsAMiniPlayerForVideo() {
    assertFalse(ActivityMinimizePolicy.showsMiniPlayer(false, ActivityMinimizePhase.Morphing, isAudioOnlyItem = false))
    assertTrue(ActivityMinimizePolicy.showsMiniPlayer(true, ActivityMinimizePhase.Idle, isAudioOnlyItem = false))
  }

  @Test
  fun handoffObjectClearsWhenTheServiceTakesOverOrTheLandingExpires() {
    MusicMiniPlayerHandoff.onServiceForeground()
    MusicMiniPlayerHandoff.onActivityMinimizeProgress(true)
    MusicMiniPlayerHandoff.onActivityPageDisposed()
    assertEquals(ActivityMinimizePhase.Landed, MusicMiniPlayerHandoff.activityMinimize.value)
    MusicMiniPlayerHandoff.onServiceForeground()
    assertEquals(ActivityMinimizePhase.Idle, MusicMiniPlayerHandoff.activityMinimize.value)

    MusicMiniPlayerHandoff.onActivityMinimizeProgress(true)
    MusicMiniPlayerHandoff.expireLanded()
    assertEquals("expiry only applies after landing", ActivityMinimizePhase.Morphing, MusicMiniPlayerHandoff.activityMinimize.value)
    MusicMiniPlayerHandoff.onActivityPageDisposed()
    MusicMiniPlayerHandoff.expireLanded()
    assertEquals(ActivityMinimizePhase.Idle, MusicMiniPlayerHandoff.activityMinimize.value)
  }

  @Test
  fun resumeCardIsNotOfferedAfterTheUserClosedTheSession() {
    assertTrue(MiniPlayerResumePolicy.shouldOffer(false, false, snapshot(closedByUser = false)))
    assertFalse(
      "X / notification close must not pop the same song back up as a second mini player",
      MiniPlayerResumePolicy.shouldOffer(false, false, snapshot(closedByUser = true)),
    )
  }

  @Test
  fun resumeCardIsNeverOfferedBehindALiveSession() {
    assertTrue(ActivityMinimizePolicy.sessionLive(true, PlaybackPhase.READY))
    assertFalse(ActivityMinimizePolicy.sessionLive(false, PlaybackPhase.READY))
    assertFalse(ActivityMinimizePolicy.sessionLive(true, PlaybackPhase.IDLE))
    assertFalse(ActivityMinimizePolicy.sessionLive(true, PlaybackPhase.STOPPING))
    // MiniPlayer passes `isServiceRunning || sessionLive`, so PlayerActivity in front still blocks it.
    assertFalse(MiniPlayerResumePolicy.shouldOffer(isServiceRunning = true, isSettingsScreen = false, snapshot = snapshot(false)))
  }

  @Test
  fun largeLibraryQueuesKeepAWindowAroundTheCurrentSong() {
    val max = AudioPlaybackResumeStore.MAX_PERSISTED_QUEUE_ITEMS
    assertTrue("a typical library must fit more than the old 100", max >= 500)
    assertEquals(0 to 40, AudioPlaybackResumeStore.queueWindow(size = 40, currentIndex = 7, max = max))

    val size = 3_000
    for (current in listOf(0, 1, 124, 125, 1_500, 2_874, 2_999)) {
      val (start, end) = AudioPlaybackResumeStore.queueWindow(size, current, max)
      assertEquals("window is exactly max items", max, end - start)
      assertTrue("current $current inside [$start,$end)", current in start until end)
      assertTrue(start >= 0 && end <= size)
    }
    // The current song sits a quarter into the window, leaving more room for "next".
    assertEquals(1_500 - max / 4, AudioPlaybackResumeStore.queueWindow(size, 1_500, max).first)
  }

  @Test
  fun everyServiceSaveCarriesTheClosedFlagAndTheSnapshotPersistsIt() {
    val root = generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz/ui/player/MediaPlaybackService.kt").isFile }
    val service = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/MediaPlaybackService.kt").readText()
    val saves = Regex("""AudioPlaybackResumeStore\.save\(([^)]*)\)""").findAll(service).map { it.groupValues[1] }.toList()
    assertEquals("schedule, now and task-removal saves", 3, saves.size)
    saves.forEach { args -> assertTrue("save must pass the user-closed flag: $args", "closedByUser = userClosedSession" in args) }
    val closeBranch = service.substringAfter("ACTION_NOTIFICATION_CLOSE,\n        ACTION_NOTIFICATION_STOP,").substringBefore("return START_NOT_STICKY")
    assertTrue("mini player X / notification close marks the session closed", "userClosedSession = true" in closeBranch)

    val store = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/AudioPlaybackResumeStore.kt").readText()
    assertTrue("""put("closedByUser", snapshot.closedByUser)""" in store)
    assertTrue("""closedByUser = root.optBoolean("closedByUser", false)""" in store)
  }
}
