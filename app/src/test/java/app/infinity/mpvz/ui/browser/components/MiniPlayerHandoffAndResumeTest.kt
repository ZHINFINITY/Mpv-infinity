/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.browser.components

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import app.infinity.mpvz.ui.player.AudioPlaybackResumeSnapshot
import app.infinity.mpvz.ui.player.AudioPlaybackResumeStore
import app.infinity.mpvz.ui.player.ClosedAudioSessionMarker
import app.infinity.mpvz.ui.player.MediaPlaybackService
import app.infinity.mpvz.ui.player.PlaybackItem
import app.infinity.mpvz.ui.player.PlaybackPhase
import app.infinity.mpvz.ui.player.PlaybackQueueState
import app.infinity.mpvz.ui.player.PlaybackResumePositionCapture
import app.infinity.mpvz.ui.player.PlaybackResumeStateCapture
import app.infinity.mpvz.ui.player.PlaybackSessionState
import app.infinity.mpvz.ui.player.RepeatMode
import app.infinity.mpvz.ui.player.persistAudioResumeSnapshotForActivity
import app.infinity.mpvz.ui.player.persistBeforePlaybackTeardown
import app.infinity.mpvz.ui.player.resumePositionPhaseForSnapshot
import app.infinity.mpvz.ui.player.saveSynchronousAudioResumeSnapshotIfAllowed
import app.infinity.mpvz.ui.player.selectResumePositionForSnapshot
import app.infinity.mpvz.ui.player.shouldResetClosedAudioSessionForMetadata
import app.infinity.mpvz.ui.player.shouldResetClosedAudioSessionForStartIntent
import app.infinity.mpvz.ui.player.shouldSkipSynchronousPlaybackStateSave
import java.io.File
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
    saves.forEach { args -> assertTrue("save must pass the captured user-closed flag: $args", "closedByUser = closedByUser" in args) }
    val scheduledSave =
      service.substringAfter("private fun schedulePlaybackStateSave(")
        .substringBefore("private fun savePlaybackStateNow()")
    val scheduledFlag = scheduledSave.indexOf("val closedByUser = isAudioSessionClosedByUser()")
    val scheduledLaunch = scheduledSave.indexOf("serviceScope.launch")
    assertTrue(scheduledFlag >= 0 && scheduledFlag < scheduledLaunch)
    val immediateSave =
      service.substringAfter("private fun savePlaybackStateNow()")
        .substringBefore("private fun savePlaybackStateSynchronously()")
    val immediateFlag = immediateSave.indexOf("val closedByUser = isAudioSessionClosedByUser()")
    val persistenceLaunch = immediateSave.indexOf("persistenceScope.launch")
    assertTrue(immediateFlag >= 0 && immediateFlag < persistenceLaunch)
    assertTrue(immediateSave.contains("playbackStateSaveJob ="))
    val stopBody = service.substringAfter("private fun stopPlaybackAndService(").substringBefore("private fun stopDetachedPlaybackIfNeeded")
    assertTrue(stopBody.contains("stopClosedSessionAndService()"))
    val closeStop = stopBody.substringAfter("private fun stopClosedSessionAndService()")
    assertTrue(closeStop.contains("persistenceScope.launch"))
    assertTrue(closeStop.contains("persistBeforePlaybackTeardown("))
    assertTrue(closeStop.contains("pendingSave?.cancelAndJoin()"))
    assertFalse("close persistence must not block the callback thread", closeStop.contains("runBlocking"))
    assertTrue(closeStop.indexOf("saveSynchronousAudioResumeSnapshotIfAllowed(") < closeStop.indexOf("PlaybackSession.stop(clearQueue = true)"))
    val closeBranch = service.substringAfter("ACTION_NOTIFICATION_CLOSE,\n        ACTION_NOTIFICATION_STOP,").substringBefore("return START_NOT_STICKY")
    assertTrue("mini player X / notification close records the closed session", "markUserClosedSession()" in closeBranch)
    assertTrue(closeBranch.contains("stopPlaybackAndService(force = true)"))
    val closeStateGetter = service.substringAfter("internal fun isAudioSessionClosedByUser()")
      .substringBefore("internal fun closedAudioSessionSnapshotFor")
    assertTrue(closeStateGetter.contains("closedAudioSessionMarker.isClosed"))
    assertFalse(closeStateGetter.contains("activeInstance"))

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
    val activityResetIndex = activitySave.indexOf("MediaPlaybackService.resetClosedAudioSessionForNewPlayback(")
    val activityClosedFlagIndex = activitySave.indexOf("MediaPlaybackService.isAudioSessionClosedByUser()")
    assertTrue(activityResetIndex >= 0 && activityResetIndex < activityClosedFlagIndex)
    assertTrue(activitySave.contains("MediaPlaybackService.isAudioSessionClosedByUser()"))
    assertTrue(activitySave.contains("closedByUser = closedByUser"))
    val syncSave =
      service.substringAfter("private fun savePlaybackStateSynchronously()")
        .substringBefore("private suspend fun persistPlaybackState")
    assertTrue(syncSave.contains("shouldSkipSynchronousPlaybackStateSave("))
    assertTrue(syncSave.contains("saveSynchronousAudioResumeSnapshotIfAllowed("))
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

    assertTrue(marker.isClosed(hasCurrentItem = true))
    assertTrue(marker.isCurrentClosedSession(7L, current))
    assertEquals(29.5, marker.snapshotFor(current)?.positionSeconds ?: -1.0, 0.0)
    assertFalse("the process marker clears only once the queue is empty", marker.isClosed(hasCurrentItem = false))
    assertFalse(marker.isCurrentClosedSession(7L, current))
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
    assertTrue(marker.isClosed(hasCurrentItem = true))

    val incomingItem = item("incoming-open")
    val incomingState =
      PlaybackSessionState(
        phase = PlaybackPhase.READY,
        generation = 21L,
        currentItem = incomingItem,
      )
    val playbackCapture =
      PlaybackResumeStateCapture(
        queueState = queue(listOf(incomingItem)),
        sessionState = incomingState,
        position = PlaybackResumePositionCapture(positionSeconds = 6.75, isKnown = true),
        paused = false,
      )

    assertTrue(marker.resetForNewPlayback(incomingState, incomingItem.stableId, incomingItem.originalUri))
    val closedByUser = marker.isClosed(hasCurrentItem = true)
    val closedSnapshot = if (closedByUser) marker.snapshotFor(incomingItem) else null
    assertFalse(closedByUser)
    assertTrue(
      persistAudioResumeSnapshotForActivity(
        context = application,
        playbackCapture = playbackCapture,
        closedByUser = closedByUser,
        closedSnapshot = closedSnapshot,
      ),
    )

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

    assertTrue(marker.isClosed(hasCurrentItem = true))
    assertTrue(
      persistAudioResumeSnapshotForActivity(
        context = application,
        playbackCapture = capture,
        closedByUser = marker.isClosed(hasCurrentItem = true),
        closedSnapshot = marker.snapshotFor(currentItem),
      ),
    )

    val restored = requireNotNull(AudioPlaybackResumeStore.load(application))
    assertEquals(32.5, restored.positionSeconds, 0.0)
    assertTrue(restored.paused)
    assertTrue(restored.closedByUser)
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

    runBlocking {
      persistBeforePlaybackTeardown(
        persist = {
          saveSucceeded = AudioPlaybackResumeStore.save(
            application,
            activeQueue,
            22.0,
            paused = true,
            closedByUser = true,
          )
        },
        teardown = { activeQueue = queue(emptyList()) },
      )
    }

    assertTrue(saveSucceeded)
    assertNull(activeQueue.currentItem)
    val restored = AudioPlaybackResumeStore.load(application)
    assertNotNull(restored)
    assertTrue(restored!!.closedByUser)
    assertEquals("closed", restored.queueState.currentItem?.stableId)
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
