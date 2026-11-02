/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.components

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import app.infinity.mpvz.ui.player.AudiobookPlaybackInfo
import app.infinity.mpvz.ui.player.AudioPlaybackResumeSnapshot
import app.infinity.mpvz.ui.player.AudioPlaybackResumeStore
import app.infinity.mpvz.ui.player.ClosedAudioSessionMarker
import app.infinity.mpvz.ui.player.DirectAudiobookPlaybackInfo
import app.infinity.mpvz.ui.player.MediaPlaybackService
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlaybackPhase
import app.infinity.mpvz.ui.player.PlaybackQueueState
import app.infinity.mpvz.ui.player.PlaybackResumePositionCapture
import app.infinity.mpvz.ui.player.PlaybackResumeStateCapture
import app.infinity.mpvz.ui.player.PlaybackSessionState
import app.infinity.mpvz.ui.player.RepeatMode
import app.infinity.mpvz.ui.player.persistBeforePlaybackTeardown
import app.infinity.mpvz.ui.player.resumePositionPhaseForSnapshot
import app.infinity.mpvz.ui.player.saveSynchronousAudioResumeSnapshotIfAllowed
import app.infinity.mpvz.ui.player.selectResumePositionForSnapshot
import app.infinity.mpvz.ui.player.shouldMarkClosedAudioSession
import app.infinity.mpvz.ui.player.shouldResetClosedAudioSessionForMetadata
import app.infinity.mpvz.ui.player.shouldResetClosedAudioSessionForStartIntent
import app.infinity.mpvz.ui.player.shouldSkipSynchronousPlaybackStateSave
import app.infinity.mpvz.ui.player.shouldTransferPendingPlaybackStateSave
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
    assertTrue("service must retain testable Activity and close save seams", saves.isNotEmpty())
    saves.forEach { args ->
      assertTrue(
        "save must pass the captured user-closed flag: $args",
        "closedByUser = closedByUser" in args || "closedByUser = save.closedByUser" in args,
      )
    }
    val scheduledSave =
      service.substringAfter("private fun schedulePlaybackStateSave(")
        .substringBefore("private fun savePlaybackStateNow()")
    val scheduledCapture = scheduledSave.indexOf("capturePendingPlaybackStateSave(playbackCapture, identifier)")
    val scheduledLaunch = scheduledSave.indexOf("serviceScope.launch")
    assertTrue(scheduledCapture >= 0 && scheduledCapture < scheduledLaunch)
    assertTrue(scheduledSave.contains("isAudioClosePersistencePending()"))
    val immediateSave =
      service.substringAfter("private fun savePlaybackStateNow()")
        .substringBefore("private fun savePlaybackStateSynchronously()")
    val transferCheck = immediateSave.indexOf("shouldTransferPendingPlaybackStateSave(")
    val cancelAfterTransfer = immediateSave.indexOf("previousSave?.cancel()")
    assertTrue(transferCheck >= 0 && transferCheck < cancelAfterTransfer)
    assertTrue(immediateSave.contains("persistPlaybackStateSave(pending)"))
    assertTrue(immediateSave.contains("pending?.takeIf { phase in setOf(PlaybackPhase.STOPPING, PlaybackPhase.IDLE) }?.identifier"))
    assertTrue(immediateSave.contains("playbackStateSaveJob ="))
    val stopBody = service.substringAfter("private fun stopPlaybackAndService(").substringBefore("private fun stopDetachedPlaybackIfNeeded")
    assertTrue(stopBody.contains("stopClosedSessionAndService()"))
    val closeStop = stopBody.substringAfter("private fun stopClosedSessionAndService()")
    assertTrue(closeStop.contains("persistenceScope.launch"))
    assertTrue(closeStop.contains("persistBeforePlaybackTeardown("))
    assertTrue(closeStop.contains("pendingSave?.cancelAndJoin()"))
    assertTrue(closeStop.contains("persistIfCloseIsCurrent("))
    assertTrue(closeStop.contains("resetUserClosedSessionForNewPlayback(liveState"))
    assertTrue(closeStop.contains("closedAudioSessionMarker.snapshotFor(null)"))
    assertFalse("close persistence must not block the callback thread", closeStop.contains("runBlocking"))
    val persistBlock = closeStop.substringAfter("persist = {").substringBefore("teardown = ::teardown")
    assertTrue(persistBlock.contains("saveSynchronousAudioResumeSnapshotIfAllowed("))
    assertTrue(closeStop.contains("teardown = ::teardown"))
    val closeBranch = service.substringAfter("ACTION_NOTIFICATION_CLOSE,\n        ACTION_NOTIFICATION_STOP,").substringBefore("return START_NOT_STICKY")
    assertTrue("mini player X / notification close records the closed session", "markUserClosedSession()" in closeBranch)
    assertTrue(closeBranch.contains("stopPlaybackAndService(force = true)"))
    val markerBody = service.substringAfter("private fun markUserClosedSession()")
      .substringBefore("private fun resetUserClosedSessionForNewPlayback(")
    assertTrue(markerBody.contains("if (!shouldMarkClosedAudioSession(currentItem)) return"))
    val closeStateGetter = service.substringAfter("internal fun isAudioSessionClosedByUser()")
      .substringBefore("internal fun closedAudioSessionSnapshotFor")
    assertTrue(closeStateGetter.contains("if (!shouldMarkClosedAudioSession(currentItem)) return false"))
    assertTrue(closeStateGetter.contains("closedAudioSessionMarker.isClosed"))
    assertFalse(closeStateGetter.contains("activeInstance"))
    val newItemWiring = service.substringAfter("private fun applySessionItem(").substringBefore("val identity =")
    assertTrue(newItemWiring.contains("resetUserClosedSessionForNewPlayback("))

    val closePersistMethod = service.substringAfter("fun persistIfCloseIsCurrent(")
      .substringBefore("fun persistResumeSnapshotIfCurrent(")
    assertTrue(closePersistMethod.contains("withResumePersistenceLock"))
    assertFalse(closePersistMethod.contains("synchronized(this)"))

    val store = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/AudioPlaybackResumeStore.kt").readText()
    assertTrue("""put("closedByUser", snapshot.closedByUser)""" in store)
    assertTrue("""closedByUser = root.optBoolean("closedByUser", false)""" in store)
    assertTrue("""if (queueChanged) editor.putString(QUEUE_KEY, queueRecord.encoded)""" in store)
    assertTrue("""editor.putString(STATE_KEY, encodedState)""" in store)
    assertTrue("""previousQueue.items != savedItems""" in store)

    val activity = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").readText()
    val activitySave =
      activity.substringAfter("private fun persistAudioPlaybackSnapshot()")
        .substringBefore("private fun attachToSavedPlaybackSessionIfValid")
    assertTrue(activitySave.contains("MediaPlaybackService.persistActivityAudioResumeSnapshot("))
    assertFalse(activitySave.contains("MediaPlaybackService.isAudioSessionClosedByUser()"))
    val syncSave =
      service.substringAfter("private fun savePlaybackStateSynchronously()")
        .substringBefore("private suspend fun persistPlaybackState")
    assertTrue(syncSave.contains("shouldSkipSynchronousPlaybackStateSave("))
    assertTrue(syncSave.contains("saveSynchronousAudioResumeSnapshotIfAllowed("))
    assertTrue(syncSave.contains("persistResumeSnapshotIfCurrent("))
    val saveGuard =
      service.substringAfter("internal fun shouldSkipSynchronousPlaybackStateSave(")
        .substringBefore("internal fun saveSynchronousAudioResumeSnapshotIfAllowed(")
    assertTrue(saveGuard.contains("nativeRendererOwner || identifier.isBlank()"))
  }

  @Test
  fun closedAudioSavePolicyAllowsOnlyExplicitAudioForNativeAndBlankIdentity() {
    val audioItem = PlaybackItem.fromUri(
      uri = "file:///audio.mp3",
      stableId = "audio",
      mimeType = "audio/mpeg",
    )
    val videoItem = PlaybackItem.fromUri(
      uri = "file:///video.mp4",
      stableId = "video",
      mimeType = "video/mp4",
    )
    val audiobookItem =
      audioItem.copy(
        audiobook = AudiobookPlaybackInfo(bookId = 123L, trackId = 456L),
      )

    val nativeBlankIdSaveIsSkipped =
      shouldSkipSynchronousPlaybackStateSave(
        nativeRendererOwner = true,
        identifier = "",
        closedByUser = true,
        currentItem = audioItem,
      )
    assertFalse(nativeBlankIdSaveIsSkipped)
    assertFalse(
      shouldSkipSynchronousPlaybackStateSave(
        nativeRendererOwner = false,
        identifier = "",
        closedByUser = true,
        currentItem = audioItem,
      ),
    )
    assertTrue(
      shouldSkipSynchronousPlaybackStateSave(
        nativeRendererOwner = true,
        identifier = "audio",
        closedByUser = false,
        currentItem = audioItem,
      ),
    )
    assertTrue(
      shouldSkipSynchronousPlaybackStateSave(
        nativeRendererOwner = true,
        identifier = "",
        closedByUser = true,
        currentItem = videoItem,
      ),
    )
    assertFalse(
      shouldSkipSynchronousPlaybackStateSave(
        nativeRendererOwner = false,
        identifier = "video",
        closedByUser = true,
        currentItem = videoItem,
      ),
    )
    assertTrue(shouldMarkClosedAudioSession(audioItem))
    assertFalse(shouldMarkClosedAudioSession(videoItem))
    assertFalse(AudioPlaybackResumeStore.canPersistCurrentItem(audiobookItem))
    assertFalse(shouldMarkClosedAudioSession(audiobookItem))
    assertFalse(shouldMarkClosedAudioSession(null))
  }

  @Test
  fun closedSessionResetsOnlyForAcceptedLivePlaybackMetadata() {
    val current = PlaybackItem.fromUri(
      uri = "file:///current.mp3",
      stableId = "current",
      mimeType = "audio/mpeg",
    )
    val acceptedMetadata = "current" to "file:///current.mp3"
    val closedGeneration = 7L
    listOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND).forEach { phase ->
      assertTrue(
        "matching metadata resets only for a live $phase session",
        shouldResetClosedAudioSessionForMetadata(
          PlaybackSessionState(phase = phase, generation = closedGeneration + 1, currentItem = current),
          closedGeneration,
          acceptedMetadata.first,
          acceptedMetadata.second,
        ),
      )
    }
    listOf(PlaybackPhase.IDLE, PlaybackPhase.STOPPING, PlaybackPhase.ERROR).forEach { phase ->
      assertFalse(
        "retained queue metadata cannot reopen a closed $phase session",
        shouldResetClosedAudioSessionForMetadata(
          PlaybackSessionState(phase = phase, generation = closedGeneration + 1, currentItem = current),
          closedGeneration,
          acceptedMetadata.first,
          acceptedMetadata.second,
        ),
      )
    }
    assertFalse(
      shouldResetClosedAudioSessionForMetadata(
        PlaybackSessionState(phase = PlaybackPhase.READY, generation = closedGeneration + 1, currentItem = current),
        closedGeneration,
        null,
        null,
      ),
    )
    assertFalse(
      shouldResetClosedAudioSessionForMetadata(
        PlaybackSessionState(phase = PlaybackPhase.READY, generation = closedGeneration + 1, currentItem = current),
        closedGeneration,
        "stale",
        "file:///old.mp3",
      ),
    )
    assertFalse(
      "metadata-only updates in the session that was closed must preserve the marker",
      shouldResetClosedAudioSessionForMetadata(
        PlaybackSessionState(phase = PlaybackPhase.READY, generation = closedGeneration, currentItem = current),
        closedGeneration,
        acceptedMetadata.first,
        acceptedMetadata.second,
      ),
    )
    assertFalse(
      "a missing close generation cannot be treated as a new playback",
      shouldResetClosedAudioSessionForMetadata(
        PlaybackSessionState(phase = PlaybackPhase.READY, generation = closedGeneration + 1, currentItem = current),
        null,
        acceptedMetadata.first,
        acceptedMetadata.second,
      ),
    )

    assertTrue(shouldResetClosedAudioSessionForStartIntent(null, "current", "file:///current.mp3"))
    assertTrue(shouldResetClosedAudioSessionForStartIntent(Intent.ACTION_VIEW, "current", "file:///current.mp3"))
    assertFalse(
      "opening the notification player is re-entry, not a new playback intent",
      shouldResetClosedAudioSessionForStartIntent(MediaPlaybackService.ACTION_OPEN_PLAYER, "current", "file:///current.mp3"),
    )
    assertFalse(
      shouldResetClosedAudioSessionForStartIntent(MediaPlaybackService.ACTION_NOTIFICATION_CLOSE, "current", "file:///current.mp3"),
    )
    assertFalse(
      shouldResetClosedAudioSessionForStartIntent(MediaPlaybackService.ACTION_NOTIFICATION_STOP, "current", "file:///current.mp3"),
    )
    assertFalse(shouldResetClosedAudioSessionForStartIntent(Intent.ACTION_MEDIA_BUTTON, "current", "file:///current.mp3"))
    assertFalse(shouldResetClosedAudioSessionForStartIntent(null, null, null))

    val root = generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "app/src/main/java/app/infinity/mpvz/ui/player/MediaPlaybackService.kt").isFile }
    val service = File(root, "app/src/main/java/app/infinity/mpvz/ui/player/MediaPlaybackService.kt").readText()
    val onStart = service.substringAfter("override fun onStartCommand(").substringBefore("\n  override fun onBind(")
    val closeBranch =
      onStart.substringAfter("ACTION_NOTIFICATION_CLOSE,")
        .substringBefore("else -> MediaButtonReceiver.handleIntent")
    assertTrue(closeBranch.contains("markUserClosedSession()"))
    assertFalse(closeBranch.contains("userClosedSession = false"))
    assertTrue(onStart.contains("shouldResetClosedAudioSessionForStartIntent(it.action, identifier, uri)"))
    assertTrue(onStart.contains("resetUserClosedSessionForNewPlayback(PlaybackSession.state.value, identifier, uri)"))
    val metadataSync = service.substringAfter("fun setMediaInfo(").substringBefore("private fun replaceOwnedThumbnail(")
    val resetPolicyIndex = metadataSync.indexOf("resetUserClosedSessionForNewPlayback(sessionState, identifier, uri)")
    assertTrue(resetPolicyIndex >= 0)
    assertTrue(resetPolicyIndex < metadataSync.indexOf("if (isCurrentRadioItem())"))
    assertTrue(metadataSync.contains("val currentItem = sessionState.currentItem"))
  }

  @Test
  fun closedMarkerSurvivesServiceInstanceLossUntilTheQueueClears() {
    val current = PlaybackItem.fromUri(
      uri = "file:///closed.mp3",
      stableId = "closed",
      mimeType = "audio/mpeg",
    )
    val marker = ClosedAudioSessionMarker()
    marker.mark(generation = 7L, currentItem = current, positionSeconds = 29.5, paused = true)
    val currentState = PlaybackSessionState(phase = PlaybackPhase.READY, generation = 7L, currentItem = current)

    assertTrue(marker.isClosed(currentState, current))
    assertTrue(marker.isCurrentClosedSession(7L, current))
    assertEquals(29.5, marker.snapshotFor(current)?.positionSeconds ?: -1.0, 0.0)
    val emptyState = PlaybackSessionState(phase = PlaybackPhase.IDLE, generation = 7L, currentItem = null)
    assertFalse("the process marker clears only once the queue is empty", marker.isClosed(emptyState, null))
    assertFalse(marker.isCurrentClosedSession(7L, current))
  }

  @Test
  fun laterCloseAfterFinishedPersistenceRefreshesTheSameSessionSnapshot() {
    val current =
      PlaybackItem.fromUri(
        uri = "file:///retry-close.mp3",
        stableId = "retry-close",
        mimeType = "audio/mpeg",
      )
    val marker = ClosedAudioSessionMarker()
    marker.mark(generation = 7L, currentItem = current, positionSeconds = 29.5, paused = false)

    assertTrue(marker.beginClosePersistence(7L, current))
    // Duplicate close delivery during an active write keeps the pre-pause capture.
    marker.mark(generation = 7L, currentItem = current, positionSeconds = 0.0, paused = true)
    val pending = requireNotNull(marker.snapshotFor(current))
    assertEquals(29.5, pending.positionSeconds, 0.0)
    assertFalse(pending.paused)

    // A failed persistence attempt leaves playback intact; a later user close must recapture it.
    marker.finishClosePersistence(7L, current)
    assertFalse(marker.isClosePersistencePending(7L, current))
    marker.mark(generation = 7L, currentItem = current, positionSeconds = 35.25, paused = true)
    val retried = requireNotNull(marker.snapshotFor(current))
    assertEquals(7L, retried.generation)
    assertEquals(35.25, retried.positionSeconds, 0.0)
    assertTrue(retried.paused)

    // PlaybackSession.load advances generation, so a later session for the item gets a fresh capture.
    marker.mark(generation = 8L, currentItem = current, positionSeconds = 4.5, paused = true)
    val resumed = requireNotNull(marker.snapshotFor(current))
    assertEquals(8L, resumed.generation)
    assertEquals(4.5, resumed.positionSeconds, 0.0)
    assertTrue(resumed.paused)
  }

  @Test
  fun aReplacementSessionDuringStoppingGetsItsOwnClosePersistenceToken() {
    val marker = ClosedAudioSessionMarker()
    val outgoing =
      PlaybackItem.fromUri(
        uri = "file:///outgoing-stopping.mp3",
        stableId = "outgoing-stopping",
        mimeType = "audio/mpeg",
      )
    marker.mark(generation = 71L, currentItem = outgoing, positionSeconds = 19.0, paused = true)
    assertTrue(marker.beginClosePersistence(71L, outgoing))
    assertTrue(marker.isClosePersistencePending(71L, outgoing))

    val incoming =
      PlaybackItem.fromUri(
        uri = "file:///incoming-stopping.mp3",
        stableId = "incoming-stopping",
        mimeType = "audio/mpeg",
      )
    val incomingState = PlaybackSessionState(phase = PlaybackPhase.STOPPING, generation = 72L, currentItem = incoming)
    assertTrue(marker.resetForNewPlayback(incomingState, incoming.stableId, incoming.originalUri))
    assertFalse(marker.isClosePersistencePending(72L, incoming))
    marker.mark(generation = 72L, currentItem = incoming, positionSeconds = 3.0, paused = false)
    assertTrue(marker.beginClosePersistence(72L, incoming))

    marker.finishClosePersistence(71L, outgoing)
    assertTrue("the old close completion must not clear the replacement close token", marker.isClosePersistencePending(72L, incoming))
    var staleCloseWrote = false
    assertFalse(marker.persistIfCloseIsCurrent(71L, outgoing) { staleCloseWrote = true; false })
    assertFalse(staleCloseWrote)
    assertTrue(marker.persistIfCloseIsCurrent(72L, incoming) { true })
  }

  @Test
  fun sameUriWithDifferentStableIdDoesNotReuseClosedMarker() {
    val outgoing = PlaybackItem.fromUri(uri = "file:///same.mp3", stableId = "outgoing", mimeType = "audio/mpeg")
    val incoming = PlaybackItem.fromUri(uri = "file:///same.mp3", stableId = "incoming", mimeType = "audio/mpeg")
    val marker = ClosedAudioSessionMarker()
    marker.mark(generation = 81L, currentItem = outgoing, positionSeconds = 29.0, paused = true)
    val incomingState = PlaybackSessionState(phase = PlaybackPhase.STOPPING, generation = 82L, currentItem = incoming)

    assertFalse(marker.isCurrentClosedSession(82L, incoming))
    assertNull(marker.snapshotFor(incoming))
    assertTrue(marker.resetForNewPlayback(incomingState, incoming.stableId, incoming.originalUri))
    assertNull(marker.snapshotFor(outgoing))
  }

  @Test
  fun replacementItemUsesPendingRestoreInsteadOfOutgoingMpvPosition() {
    assertEquals(PlaybackPhase.LOADING, resumePositionPhaseForSnapshot(PlaybackPhase.READY, loadedItemMatchesQueue = false))
    assertEquals(PlaybackPhase.READY, resumePositionPhaseForSnapshot(PlaybackPhase.READY, loadedItemMatchesQueue = true))

    val withoutRestore =
      selectResumePositionForSnapshot(
        phase = PlaybackPhase.LOADING,
        pendingRestoreSeconds = null,
        nativePositionSeconds = 84.0,
        fallbackSeconds = 0.0,
      )
    assertEquals(0.0, withoutRestore.positionSeconds, 0.0)
    assertFalse(withoutRestore.isKnown)

    val withRestore =
      selectResumePositionForSnapshot(
        phase = PlaybackPhase.LOADING,
        pendingRestoreSeconds = 17.25,
        nativePositionSeconds = 84.0,
        fallbackSeconds = 0.0,
      )
    assertEquals(17.25, withRestore.positionSeconds, 0.0)
    assertTrue(withRestore.isKnown)
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioPlaybackResumeStorePersistenceTest {
  private val application: Application
    get() = RuntimeEnvironment.getApplication()
  private lateinit var preferences: SharedPreferences

  @Before
  fun setUp() {
    preferences = application.getSharedPreferences("audio_resume_split_test", Context.MODE_PRIVATE)
    preferences.edit().clear().commit()
    AudioPlaybackResumeStore.setPreferencesForTests(preferences)
  }

  @After
  fun tearDown() {
    AudioPlaybackResumeStore.clear(application)
    preferences.edit().clear().commit()
    AudioPlaybackResumeStore.setPreferencesForTests(null)
  }

  private fun item(id: String): PlaybackItem =
    PlaybackItem.fromUri(
      uri = "file:///$id.mp3",
      stableId = id,
      title = id,
      artist = "Artist $id",
      mimeType = "audio/mpeg",
      headers = mapOf("Authorization" to "Bearer $id"),
    )

  private fun queue(items: List<PlaybackItem>, currentIndex: Int = 0): PlaybackQueueState =
    PlaybackQueueState(
      items = items,
      currentIndex = currentIndex,
      isExplicitQueue = items.size > 1,
      repeatMode = RepeatMode.ALL,
      shuffleEnabled = items.size > 1,
    )

  private fun awaitThreadCompletionOrBlockingState(
    thread: Thread,
    timeoutMillis: Long = 5_000L,
  ): Thread.State {
    // Bound the coordination loop so a stuck worker fails instead of hanging the test suite.
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (thread.isAlive) {
      val state = thread.state
      if (
        state == Thread.State.BLOCKED ||
        state == Thread.State.WAITING ||
        state == Thread.State.TIMED_WAITING
      ) return state
      if (System.nanoTime() >= deadlineNanos) return state
      Thread.yield()
    }
    return Thread.State.TERMINATED
  }

  private fun isWaitingInsideResumePersistenceLock(thread: Thread): Boolean {
    if (thread.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)) return false
    val stack = thread.stackTrace
    val insideMarkerLock = stack.any {
      it.className.endsWith("ClosedAudioSessionMarker") && it.methodName == "withResumePersistenceLock"
    }
    val parkedOnJavaLock = stack.any {
      it.className.startsWith("java.util.concurrent.locks.") &&
        it.methodName in setOf("lock", "acquire", "parkAndCheckInterrupt")
    }
    return insideMarkerLock && parkedOnJavaLock
  }

  @Test
  fun directAudiobookMarkersRoundTripInTheAudioResumeQueue() {
    val firstMarker =
      DirectAudiobookPlaybackInfo(
        queueIdentity = "direct-folder",
        trackIdentity = "chapter-one",
      )
    val currentMarker =
      DirectAudiobookPlaybackInfo(
        queueIdentity = "direct-folder",
        trackIdentity = "chapter-two",
      )
    val currentItem = item("direct-track-two").copy(directAudiobook = currentMarker)
    val directQueue =
      queue(
        listOf(
          item("direct-track-one").copy(directAudiobook = firstMarker),
          currentItem,
        ),
        currentIndex = 1,
      )

    assertTrue(AudioPlaybackResumeStore.canPersistCurrentItem(currentItem))
    assertTrue(shouldMarkClosedAudioSession(currentItem))
    assertTrue(
      AudioPlaybackResumeStore.save(
        context = application,
        queueState = directQueue,
        positionSeconds = 44.5,
        paused = true,
        closedByUser = true,
      ),
    )

    val restored = requireNotNull(AudioPlaybackResumeStore.load(application))
    assertEquals(
      listOf(firstMarker, currentMarker),
      restored.queueState.items.map { it.directAudiobook },
    )
    assertEquals(currentMarker, restored.currentItem?.directAudiobook)
    assertEquals(44.5, restored.positionSeconds, 0.0)
    assertTrue(restored.closedByUser)
  }

  @Test
  fun synchronousSaveHelperPersistsClosedAudioForNativeOwnerWithoutIdentifier() {
    val closedAudioQueue = queue(listOf(item("native-closed")))
    assertTrue(
      saveSynchronousAudioResumeSnapshotIfAllowed(
        context = application,
        nativeRendererOwner = true,
        identifier = "",
        queueState = closedAudioQueue,
        positionSeconds = 12.0,
        paused = true,
        closedByUser = true,
      ),
    )
    val restored = requireNotNull(AudioPlaybackResumeStore.load(application))
    assertTrue(restored.closedByUser)
    assertEquals(12.0, restored.positionSeconds, 0.0)
    assertTrue(restored.paused)
  }

  @Test
  fun newQueueItemResetsClosedMarkerBeforeActivityPauseSnapshotSave() {
    val marker = ClosedAudioSessionMarker()
    val outgoingItem = item("outgoing-closed")
    marker.mark(generation = 20L, currentItem = outgoingItem, positionSeconds = 41.0, paused = true)
    val outgoingState = PlaybackSessionState(phase = PlaybackPhase.READY, generation = 20L, currentItem = outgoingItem)
    assertTrue(marker.isClosed(outgoingState, outgoingItem))

    val incomingItem = item("incoming-open")
    val incomingState =
      PlaybackSessionState(
        phase = PlaybackPhase.STOPPING,
        generation = 21L,
        currentItem = outgoingItem,
      )
    val playbackCapture =
      PlaybackResumeStateCapture(
        queueState = queue(listOf(incomingItem)),
        sessionState = incomingState,
        position = PlaybackResumePositionCapture(positionSeconds = 6.75, isKnown = true),
        paused = false,
      )

    assertTrue(marker.persistActivitySnapshot(application, playbackCapture))
    assertNull("the Activity queue item replaces a stale state projection before writing", marker.snapshotFor(outgoingItem))
    assertFalse(marker.isClosed(incomingState, incomingItem))

    val restored = requireNotNull(AudioPlaybackResumeStore.load(application))
    assertEquals("incoming-open", restored.queueState.currentItem?.stableId)
    assertEquals(6.75, restored.positionSeconds, 0.0)
    assertFalse(restored.closedByUser)
  }

  @Test
  fun activitySaveAfterServiceLossKeepsTheClosedSnapshotPosition() {
    val currentItem = item("closed-after-service")
    val marker = ClosedAudioSessionMarker()
    marker.mark(generation = 31L, currentItem = currentItem, positionSeconds = 32.5, paused = true)
    val capture =
      PlaybackResumeStateCapture(
        queueState = queue(listOf(currentItem)),
        sessionState =
          PlaybackSessionState(
            phase = PlaybackPhase.STOPPING,
            generation = 31L,
            currentItem = currentItem,
          ),
        position = PlaybackResumePositionCapture(positionSeconds = 0.0, isKnown = false),
        paused = true,
      )

    assertTrue(marker.isClosed(capture.sessionState, currentItem))
    assertTrue(marker.persistActivitySnapshot(application, capture))

    val restored = requireNotNull(AudioPlaybackResumeStore.load(application))
    assertEquals(32.5, restored.positionSeconds, 0.0)
    assertTrue(restored.paused)
    assertTrue(restored.closedByUser)
  }

  @Test
  fun activitySaveInvalidatesAnOlderQueuedServiceResumeWrite() {
    val marker = ClosedAudioSessionMarker()
    val outgoingItem = item("queued-service-old")
    val incomingItem = item("activity-new")
    val oldSequence = marker.reserveResumeSaveSequence()
    val oldSession = PlaybackSessionState(phase = PlaybackPhase.READY, generation = 41L, currentItem = outgoingItem)
    val incomingCapture =
      PlaybackResumeStateCapture(
        queueState = queue(listOf(incomingItem)),
        sessionState = PlaybackSessionState(phase = PlaybackPhase.READY, generation = 42L, currentItem = incomingItem),
        position = PlaybackResumePositionCapture(positionSeconds = 8.25, isKnown = true),
        paused = false,
      )

    assertTrue(marker.persistActivitySnapshot(application, incomingCapture))
    assertFalse(
      marker.persistResumeSnapshotIfCurrent(
        sessionState = oldSession,
        currentItem = outgoingItem,
        closedByUser = false,
        resumeSaveSequence = oldSequence,
      ) {
        AudioPlaybackResumeStore.save(application, queue(listOf(outgoingItem)), 3.0, paused = false, closedByUser = false)
      },
    )

    val restored = requireNotNull(AudioPlaybackResumeStore.load(application))
    assertEquals("activity-new", restored.queueState.currentItem?.stableId)
    assertEquals(8.25, restored.positionSeconds, 0.0)
  }

  @Test
  fun activityResetAndSaveCannotBeOvertakenByAnOlderClosedSnapshotWrite() {
    val marker = ClosedAudioSessionMarker()
    val outgoingItem = item("close-race-old")
    marker.mark(generation = 81L, currentItem = outgoingItem, positionSeconds = 22.0, paused = true)
    assertTrue(marker.beginClosePersistence(81L, outgoingItem))

    val closeEntered = CountDownLatch(1)
    val releaseClose = CountDownLatch(1)
    val closeFailure = AtomicReference<Throwable?>(null)
    val closeThread = Thread {
      try {
        assertTrue(
          marker.persistIfCloseIsCurrent(81L, outgoingItem) {
            closeEntered.countDown()
            assertTrue(releaseClose.await(30, TimeUnit.SECONDS))
            val committed = AudioPlaybackResumeStore.save(
              application,
              queue(listOf(outgoingItem)),
              22.0,
              paused = true,
              closedByUser = true,
            )
            assertTrue(committed)
            committed
          },
        )
      } catch (error: Throwable) {
        closeFailure.set(error)
      }
    }
    val markerReadStarted = CountDownLatch(1)
    val markerReadResult = AtomicBoolean(false)
    var markerReadThread: Thread? = null
    val activityStarted = CountDownLatch(1)
    val activityFailure = AtomicReference<Throwable?>(null)
    val activityResult = AtomicBoolean(false)
    var activityThread: Thread? = null

    try {
      closeThread.isDaemon = true
      closeThread.start()
      assertTrue(closeEntered.await(5, TimeUnit.SECONDS))

      val markerReader = Thread {
        markerReadStarted.countDown()
        markerReadResult.set(marker.isClosePersistencePending(81L, outgoingItem))
      }
      markerReader.isDaemon = true
      markerReadThread = markerReader
      markerReader.start()
      assertTrue(markerReadStarted.await(5, TimeUnit.SECONDS))
      val markerReadStateBeforeRelease = awaitThreadCompletionOrBlockingState(markerReader)
      assertEquals(
        "marker state query must complete while the close write is held",
        Thread.State.TERMINATED,
        markerReadStateBeforeRelease,
      )

      val incomingItem = item("close-race-new")
      val incomingCapture =
        PlaybackResumeStateCapture(
          queueState = queue(listOf(incomingItem)),
          sessionState = PlaybackSessionState(phase = PlaybackPhase.READY, generation = 82L, currentItem = incomingItem),
          position = PlaybackResumePositionCapture(positionSeconds = 7.5, isKnown = true),
          paused = false,
        )
      val activityWriter = Thread {
        activityStarted.countDown()
        try {
          activityResult.set(marker.persistActivitySnapshot(application, incomingCapture))
        } catch (error: Throwable) {
          activityFailure.set(error)
        }
      }
      activityWriter.isDaemon = true
      activityThread = activityWriter
      activityWriter.start()
      assertTrue(activityStarted.await(5, TimeUnit.SECONDS))
      val activityStateBeforeRelease = awaitThreadCompletionOrBlockingState(activityWriter)
      assertTrue(
        "Activity writer should be waiting inside the resume lock (state=$activityStateBeforeRelease)",
        isWaitingInsideResumePersistenceLock(activityWriter),
      )
    } finally {
      releaseClose.countDown()
      closeThread.join(5_000)
      markerReadThread?.join(5_000)
      activityThread?.join(5_000)
    }

    assertFalse(closeThread.isAlive)
    assertFalse(requireNotNull(activityThread).isAlive)
    assertFalse(requireNotNull(markerReadThread).isAlive)
    closeFailure.get()?.let { throw AssertionError("closed snapshot write failed", it) }
    activityFailure.get()?.let { throw AssertionError("Activity snapshot write failed", it) }
    assertTrue(markerReadResult.get())
    assertTrue(activityResult.get())

    val restored = requireNotNull(AudioPlaybackResumeStore.load(application))
    assertEquals("close-race-new", restored.queueState.currentItem?.stableId)
    assertEquals(7.5, restored.positionSeconds, 0.0)
    assertFalse(restored.closedByUser)
  }

  @Test
  fun pendingPlaybackSnapshotTransfersOnlyToItsMatchingTeardownSession() {
    val current = item("pending-stop")
    val replacement = item("replacement-stop")
    assertTrue(
      shouldTransferPendingPlaybackStateSave(
        phase = PlaybackPhase.STOPPING,
        currentIdentifier = "pending-stop",
        currentItem = current,
        pendingIdentifier = "pending-stop",
        pendingItem = current,
      ),
    )
    assertTrue(
      shouldTransferPendingPlaybackStateSave(
        phase = PlaybackPhase.IDLE,
        currentIdentifier = "pending-stop",
        currentItem = null,
        pendingIdentifier = "pending-stop",
        pendingItem = current,
      ),
    )
    assertFalse(
      shouldTransferPendingPlaybackStateSave(
        phase = PlaybackPhase.READY,
        currentIdentifier = "pending-stop",
        currentItem = current,
        pendingIdentifier = "pending-stop",
        pendingItem = current,
      ),
    )
    assertFalse(
      shouldTransferPendingPlaybackStateSave(
        phase = PlaybackPhase.STOPPING,
        currentIdentifier = "replacement-stop",
        currentItem = replacement,
        pendingIdentifier = "pending-stop",
        pendingItem = current,
      ),
    )
  }

  @Test
  fun positionOnlySavesUpdateSmallStateWithoutRewritingTheQueue() {
    val initialQueue = queue(listOf(item("a"), item("b"), item("c")), currentIndex = 1)
    assertTrue(AudioPlaybackResumeStore.save(application, initialQueue, 12.0, paused = false, closedByUser = true))
    val firstQueueRecord = preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null)
    val firstStateRecord = preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null)
    assertNotNull(firstQueueRecord)
    assertNotNull(firstStateRecord)

    assertTrue(AudioPlaybackResumeStore.save(application, initialQueue, 13.5, paused = true, closedByUser = false))
    assertEquals(firstQueueRecord, preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertNotEquals(firstStateRecord, preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null))

    val restored = AudioPlaybackResumeStore.load(application)
    assertNotNull(restored)
    assertEquals(listOf("a", "b", "c"), restored!!.queueState.items.map { it.stableId })
    assertEquals(1, restored.queueState.currentIndex)
    assertEquals(13.5, restored.positionSeconds, 0.0001)
    assertTrue(restored.paused)
    assertFalse(restored.closedByUser)
    assertEquals(RepeatMode.ALL, restored.queueState.repeatMode)
    assertTrue(restored.queueState.shuffleEnabled)
    assertEquals(mapOf("Authorization" to "Bearer b"), restored.queueState.currentItem?.headers)

    val changedQueue = queue(listOf(item("a"), item("b"), item("replacement")), currentIndex = 1)
    assertTrue(AudioPlaybackResumeStore.save(application, changedQueue, 14.0, paused = true))
    assertNotEquals(firstQueueRecord, preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertEquals(
      listOf("a", "b", "replacement"),
      AudioPlaybackResumeStore.load(application)?.queueState?.items?.map { it.stableId },
    )

    val mutableHeaders = mutableMapOf("Authorization" to "Bearer original")
    val mutableItem =
      PlaybackItem.fromUri(
        uri = "file:///mutable.mp3",
        stableId = "mutable",
        mimeType = "audio/mpeg",
        headers = mutableHeaders,
      )
    val mutableQueue = queue(listOf(mutableItem))
    assertTrue(AudioPlaybackResumeStore.save(application, mutableQueue, 15.0, paused = false))
    val originalMutableQueueRecord = preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null)
    mutableHeaders["Authorization"] = "Bearer refreshed"
    assertTrue(AudioPlaybackResumeStore.save(application, mutableQueue, 16.0, paused = false))
    assertNotEquals(originalMutableQueueRecord, preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertEquals("Bearer refreshed", AudioPlaybackResumeStore.load(application)?.queueState?.currentItem?.headers?.get("Authorization"))
  }

  @Test
  fun mismatchedQueueAndPositionRevisionsAreNotCombined() {
    val firstQueue = queue(listOf(item("first"), item("second")))
    assertTrue(AudioPlaybackResumeStore.save(application, firstQueue, 1.0, paused = false))
    val staleState = preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null)!!

    val secondQueue = queue(listOf(item("first"), item("replacement")))
    assertTrue(AudioPlaybackResumeStore.save(application, secondQueue, 2.0, paused = false))
    preferences.edit().putString(AudioPlaybackResumeStore.STATE_KEY, staleState).commit()

    assertNull(AudioPlaybackResumeStore.load(application))
    assertNull(preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertNull(preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null))
  }

  @Test
  fun blankOriginalUriIsRejectedBeforeSaving() {
    val invalidItem =
      PlaybackItem.fromUri(
        uri = "",
        stableId = "blank-uri",
        title = "Audio",
        mimeType = "audio/mpeg",
      )

    assertFalse(AudioPlaybackResumeStore.save(application, queue(listOf(invalidItem)), 5.0, paused = true))
    assertNull(preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertNull(preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null))
  }

  @Test
  fun literalNullOriginalUriIsRejectedBeforeSaving() {
    val invalidItem =
      PlaybackItem.fromUri(
        uri = "null",
        stableId = "null-uri",
        title = "Audio",
        mimeType = "audio/mpeg",
      )

    assertFalse(AudioPlaybackResumeStore.save(application, queue(listOf(invalidItem)), 5.0, paused = true))
    assertNull(preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertNull(preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null))
  }

  @Test
  fun blankNeighborClearsPriorSnapshotWhenCurrentItemIsValid() {
    val priorQueue = queue(listOf(item("current"), item("next")), currentIndex = 0)
    assertTrue(AudioPlaybackResumeStore.save(application, priorQueue, 3.0, paused = true))
    assertNotNull(AudioPlaybackResumeStore.load(application))

    val blankNeighbor =
      PlaybackItem.fromUri(
        uri = "",
        stableId = "blank-neighbor",
        title = "Audio",
        mimeType = "audio/mpeg",
      )
    val mixedQueue = queue(listOf(item("current"), blankNeighbor), currentIndex = 0)

    assertFalse(AudioPlaybackResumeStore.save(application, mixedQueue, 3.0, paused = true))
    assertNull(AudioPlaybackResumeStore.load(application))
    assertNull(preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertNull(preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null))
  }

  @Test
  fun literalNullNeighborClearsPriorSnapshotWhenCurrentItemIsValid() {
    val priorQueue = queue(listOf(item("current"), item("next")), currentIndex = 0)
    assertTrue(AudioPlaybackResumeStore.save(application, priorQueue, 3.0, paused = true))
    assertNotNull(AudioPlaybackResumeStore.load(application))

    val literalNullNeighbor =
      PlaybackItem.fromUri(
        uri = "null",
        stableId = "null-neighbor",
        title = "Audio",
        mimeType = "audio/mpeg",
      )
    val mixedQueue = queue(listOf(item("current"), literalNullNeighbor), currentIndex = 0)

    assertFalse(AudioPlaybackResumeStore.save(application, mixedQueue, 3.0, paused = true))
    assertNull(AudioPlaybackResumeStore.load(application))
    assertNull(preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertNull(preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null))
  }

  @Test
  fun malformedSplitQueueRejectsWholeRecordInsteadOfShiftingCurrentIndex() {
    val originalQueue = queue(listOf(item("first"), item("current"), item("next")), currentIndex = 1)
    assertTrue(AudioPlaybackResumeStore.save(application, originalQueue, 9.0, paused = false))

    val queueRecord = JSONObject(preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null)!!)
    queueRecord.getJSONArray("items").put(0, JSONObject().put("originalUri", ""))
    preferences.edit().putString(AudioPlaybackResumeStore.QUEUE_KEY, queueRecord.toString()).commit()

    assertNull(AudioPlaybackResumeStore.load(application))
    assertNull(preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertNull(preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null))
  }

  @Test
  fun closedMarkerPersistsBeforePlaybackTeardown() {
    var activeQueue = queue(listOf(item("closed")))
    var saveSucceeded = false

    val persisted = runBlocking {
      persistBeforePlaybackTeardown(
        persist = {
          saveSucceeded = AudioPlaybackResumeStore.save(
            application,
            activeQueue,
            22.0,
            paused = true,
            closedByUser = true,
          )
          saveSucceeded
        },
        teardown = { activeQueue = queue(emptyList()) },
      )
    }

    assertTrue(persisted)
    assertTrue(saveSucceeded)
    assertNull(activeQueue.currentItem)
    val restored = AudioPlaybackResumeStore.load(application)
    assertNotNull(restored)
    assertTrue(restored!!.closedByUser)
    assertEquals("closed", restored.queueState.currentItem?.stableId)
  }

  @Test
  fun failedCloseSnapshotDoesNotClearThePlaybackQueue() {
    var activeQueue = queue(listOf(item("retry-close")))
    var teardownCalled = false

    val persisted = runBlocking {
      persistBeforePlaybackTeardown(
        persist = { false },
        teardown = {
          teardownCalled = true
          activeQueue = queue(emptyList())
        },
      )
    }

    assertFalse(persisted)
    assertFalse(teardownCalled)
    assertEquals("retry-close", activeQueue.currentItem?.stableId)
  }

  @Test
  fun legacySnapshotMigratesToSplitRecordsWithoutLosingResumeState() {
    val legacyItems = listOf(item("old-a"), item("old-b"))
    val legacy =
      JSONObject().apply {
        put("version", 1)
        put("positionSeconds", 31.25)
        put("paused", true)
        put("closedByUser", true)
        put("currentIndex", 1)
        put("explicitQueue", true)
        put("m3u", false)
        put("repeatMode", RepeatMode.ALL.name)
        put("shuffleEnabled", true)
        put(
          "items",
          JSONArray().apply {
            legacyItems.forEach { item ->
              put(
                JSONObject().apply {
                  put("stableId", item.stableId)
                  put("originalUri", item.originalUri)
                  put("title", item.title)
                  put("artist", item.artist)
                  put("mimeType", item.mimeType)
                  put("headers", JSONObject().put("Authorization", "Bearer ${item.stableId}"))
                },
              )
            }
          },
        )
      }.toString()
    preferences.edit().putString(AudioPlaybackResumeStore.LEGACY_SNAPSHOT_KEY, legacy).commit()

    val restored = AudioPlaybackResumeStore.load(application)
    assertNotNull(restored)
    assertEquals(listOf("old-a", "old-b"), restored!!.queueState.items.map { it.stableId })
    assertEquals(1, restored.queueState.currentIndex)
    assertEquals(31.25, restored.positionSeconds, 0.0001)
    assertTrue(restored.paused)
    assertTrue(restored.closedByUser)
    assertNotNull(preferences.getString(AudioPlaybackResumeStore.QUEUE_KEY, null))
    assertNotNull(preferences.getString(AudioPlaybackResumeStore.STATE_KEY, null))
    assertNull(preferences.getString(AudioPlaybackResumeStore.LEGACY_SNAPSHOT_KEY, null))
  }
}
