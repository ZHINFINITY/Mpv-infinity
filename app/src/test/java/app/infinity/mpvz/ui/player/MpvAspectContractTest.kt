/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAspectContractTest {
  private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()

  @Test fun mpvAspectModesStayLivePropertyWritesWithoutSurfaceRebindOrFlowMutation() {
    val viewModel = source("ui/player/PlayerViewModel.kt")
    val mpvView = source("ui/player/MPVView.kt")
    val transition = viewModel.substringAfter("fun changeVideoAspect(")
      .substringBefore("fun setCustomAspectRatio(")
    val pinnedInterpolationOptions = mpvView.substringAfter("val mpvFlowRendererOptionResult =")
      .substringBefore("val appVideoFilters =")

    assertTrue(transition.contains("setMpvAspectOverrideIfChanged"))
    assertTrue(transition.contains("setMpvAspectPanscanIfChanged"))
    assertTrue(viewModel.contains("PlaybackSession.getPropertyDouble(\"video-aspect-override\")"))
    assertTrue(viewModel.contains("PlaybackSession.getPropertyDouble(\"panscan\")"))
    assertFalse(transition.contains("bindSurface("))
    assertFalse(transition.contains("unbindSurface("))
    assertFalse(transition.contains("initializeSession("))
    assertFalse(transition.contains("mpvFlowFrameInterpolation"))
    // Pinned uses a target-FPS option; do not revive mpv's separate boolean mixer.
    assertTrue(
      pinnedInterpolationOptions.contains(
        "val targetFps = if (mpvFlowFrameInterpolationEnabled) mpvFlowTargetFps else 0",
      ),
    )
    assertTrue(
      pinnedInterpolationOptions.contains(
        "PlaybackSession.setOptionString(\"motion-interpolation\", targetFps.toString())",
      ),
    )
    assertFalse(
      pinnedInterpolationOptions.contains("PlaybackSession.setOptionString(\"interpolation\","),
    )
  }

  @Test fun mpvLoadRestoresTheSavedPerVideoAspectAfterItsCurrentGenerationCheck() {
    val activity = source("ui/player/PlayerActivity.kt")
    val loadVideoPlaybackState = activity.substringAfter("private suspend fun loadVideoPlaybackState(")
      .substringBefore("private suspend fun applyPlaybackState(")
    val applyPlaybackState = activity.substringAfter("private suspend fun applyPlaybackState(")
      .substringBefore("private suspend fun applyNativePlaybackState(")
    val applyStateCall = loadVideoPlaybackState.indexOf(
      "applyPlaybackState(state, restoreAudioTrack = positionRestoreOverride == null)",
    )
    val generationCheck = loadVideoPlaybackState.indexOf(
      "if (!PlaybackSession.isCurrentGeneration(loadGeneration)) return@runCatching false",
      applyStateCall,
    )
    val aspectGate = loadVideoPlaybackState.indexOf("restorePerVideoAspectIfCurrent(", applyStateCall)
    val aspectRestore = loadVideoPlaybackState.indexOf(
      "viewModel.restoreVideoAspect(savedState.videoAspect, savedState.customAspectRatio, showUpdate = false)",
      aspectGate,
    )
    val mainThreadGate = loadVideoPlaybackState.lastIndexOf("withContext(Dispatchers.Main.immediate)", aspectGate)
    val nextSuspension = loadVideoPlaybackState.indexOf("withContext(Dispatchers.Main)", aspectRestore)

    assertTrue(applyStateCall >= 0)
    assertTrue(generationCheck > applyStateCall)
    assertTrue(aspectGate > generationCheck)
    assertTrue(aspectRestore > aspectGate)
    assertTrue(mainThreadGate >= 0 && mainThreadGate < aspectGate)
    assertTrue(nextSuspension > aspectRestore)
    assertTrue(loadVideoPlaybackState.contains("hasSavedState = state != null"))
    assertTrue(loadVideoPlaybackState.contains("rememberVideoAspectPerVideo = playerPreferences.rememberVideoAspectPerVideo.get()"))
    assertTrue(loadVideoPlaybackState.contains("loadGenerationIsCurrent = { shouldApplyMpvAspectRestoreForLoad(loadGeneration) }"))
    assertFalse(applyPlaybackState.contains("restoreVideoAspect("))
  }

  @Test fun staleOverlappingLoadCannotApplyPerVideoAspect() {
    var currentGeneration = 41L
    val previousMediaGeneration = currentGeneration
    val appliedAspects = mutableListOf<String>()
    currentGeneration = 42L

    val previousMediaApplied =
      restorePerVideoAspectIfCurrent(
        hasSavedState = true,
        rememberVideoAspectPerVideo = true,
        loadGenerationIsCurrent = { currentGeneration == previousMediaGeneration },
      ) { appliedAspects += "previous-media" }
    val currentMediaApplied =
      restorePerVideoAspectIfCurrent(
        hasSavedState = true,
        rememberVideoAspectPerVideo = true,
        loadGenerationIsCurrent = { currentGeneration == 42L },
      ) { appliedAspects += "current-media" }

    assertFalse(previousMediaApplied)
    assertTrue(currentMediaApplied)
    assertEquals(listOf("current-media"), appliedAspects)
    assertFalse(restorePerVideoAspectIfCurrent(false, true, { true }) { appliedAspects += "missing-state" })
    assertFalse(restorePerVideoAspectIfCurrent(true, false, { true }) { appliedAspects += "disabled-pref" })
  }

  @Test fun userAspectChoiceDuringLoadingWinsOverSavedRestoreForBothEngines() {
    val loadGeneration = 42L
    var currentMpvGeneration = loadGeneration
    var userChangedMpvGeneration: Long? = null
    var currentNativeRequest = 9L
    var userChangedNativeRequest: Long? = null

    assertTrue(shouldApplyAspectRestoreForLoad(currentMpvGeneration == loadGeneration, userChangedMpvGeneration == loadGeneration))
    userChangedMpvGeneration = currentMpvGeneration
    assertFalse(shouldApplyAspectRestoreForLoad(currentMpvGeneration == loadGeneration, userChangedMpvGeneration == loadGeneration))

    assertTrue(shouldApplyAspectRestoreForLoad(currentNativeRequest == 9L, userChangedNativeRequest == 9L))
    userChangedNativeRequest = currentNativeRequest
    assertFalse(shouldApplyAspectRestoreForLoad(currentNativeRequest == 9L, userChangedNativeRequest == 9L))

    currentMpvGeneration++
    assertFalse(shouldApplyAspectRestoreForLoad(currentMpvGeneration == loadGeneration, false))
  }

  @Test fun playlistPreflightAspectChangeStaysWithVisibleItemAndAllowsNextRestore() {
    val visibleRequestGeneration = 41L
    val nextRequestGeneration = visibleRequestGeneration + 1L
    val recordedRequestGeneration =
      requestGenerationForUserAspectChange(visibleRequestGeneration, nextRequestGeneration)
    var restoredNextItem = false

    val restoreApplied =
      restorePerVideoAspectIfCurrent(
        hasSavedState = true,
        rememberVideoAspectPerVideo = true,
        loadGenerationIsCurrent = {
          shouldApplyAspectRestoreForLoad(
            loadGenerationIsCurrent = true,
            userAspectChangeOccurredForLoad = recordedRequestGeneration == nextRequestGeneration,
          )
        },
      ) { restoredNextItem = true }

    assertEquals(visibleRequestGeneration, recordedRequestGeneration)
    assertTrue(restoreApplied)
    assertTrue(restoredNextItem)
  }

  @Test fun mpvAndNativeRestorePathsConsultThePerLoadUserIntentGuard() {
    val activity = source("ui/player/PlayerActivity.kt")
    val mpvLoad = activity.substringAfter("private suspend fun loadVideoPlaybackState(")
      .substringBefore("private suspend fun applyPlaybackState(")
    val mpvGuard = activity.substringAfter("private fun shouldApplyMpvAspectRestoreForLoad(")
      .substringBefore("private fun isCurrentMediaRequest(")
    val aspectIntent = activity.substringAfter("internal fun noteUserAspectChange() {")
      .substringBefore("private fun shouldApplyMpvAspectRestoreForLoad(")
    val nativeRestore = activity.substringAfter("private suspend fun applyNativePlaybackState(")
      .substringBefore("private fun restorePlaybackPosition(")

    assertTrue(mpvLoad.contains("shouldApplyMpvAspectRestoreForLoad(loadGeneration)"))
    assertTrue(mpvGuard.contains("shouldApplyAspectRestoreForLoad("))
    assertTrue(mpvGuard.contains("aspectChangeIntent.playbackGeneration == loadGeneration"))
    assertTrue(mpvGuard.contains("mpvAspectLoadRequestGeneration[loadGeneration]"))
    assertTrue(aspectIntent.contains("visibleMediaRequestGeneration"))
    assertTrue(aspectIntent.contains("requestGenerationForUserAspectChange("))
    assertTrue(activity.contains("if (shouldApplyMpvAspectRestoreForLoad(loadGeneration))"))
    assertTrue(nativeRestore.contains("shouldApplyAspectRestoreForLoad("))
    assertTrue(nativeRestore.contains("aspectChangeIntent.mediaRequestGeneration == requestGeneration"))
    val aspectCallback = activity.substringAfter("override fun onVideoAspectChanged() {")
      .substringBefore("private var interpolationControlRequestGeneration")
    val viewModel = source("ui/player/PlayerViewModel.kt")
    val customAspect = viewModel.substringAfter("fun setCustomAspectRatio(")
      .substringBefore("fun restoreSavedVideoAspect(")
    assertTrue(aspectCallback.contains("noteUserAspectChange()"))
    assertTrue(customAspect.contains("host.onVideoAspectChanged()"))
    assertTrue(activity.contains("val aspectChangeGenerationAtCapture = userAspectChangeGeneration"))
    assertTrue(activity.contains("userAspectChangeGeneration == aspectChangeGenerationAtCapture"))
    assertTrue(activity.contains("if (key != \"video-aspect-override\")"))
    assertTrue(activity.contains("val aspectAndLoopRestored = withContext(Dispatchers.Main.immediate)"))
  }

  @Test fun nativeHandoffUsesTheRequestCapturedBeforeSuspendingStateRead() {
    val activity = source("ui/player/PlayerActivity.kt")
    val requestCapture = activity.indexOf("val handoffRequestGeneration = mediaRequestGeneration")
    val interpolationJoin = activity.indexOf("mpvInterpolationRestartJob?.join()", requestCapture)
    val interpolationGuard = activity.indexOf("if (!isCurrentHandoffRequest()) return@collect", interpolationJoin)
    val nativeInterpolationJoin = activity.indexOf("nativeInterpolationRestartJob?.join()", interpolationGuard)
    val nativeInterpolationGuard = activity.indexOf("if (!isCurrentHandoffRequest()) return@collect", nativeInterpolationJoin)
    val stateSave = activity.indexOf("saveVideoPlaybackState(fileName, immediate = true)?.join()", nativeInterpolationGuard)
    val stateSaveGuard = activity.indexOf("if (!isCurrentHandoffRequest()) return@collect", stateSave)
    val stateRead = activity.indexOf("playbackStateRepository.getVideoDataByTitle", stateSaveGuard)
    val stateReadGuard = activity.indexOf("if (!isCurrentHandoffRequest()) return@collect", stateRead)
    val nativeApply =
      activity.indexOf("applyNativePlaybackState(handoffState, handoffRequestGeneration)", stateReadGuard)

    assertTrue(requestCapture >= 0)
    assertTrue(interpolationJoin > requestCapture)
    assertTrue(interpolationGuard > interpolationJoin)
    assertTrue(nativeInterpolationJoin > interpolationGuard)
    assertTrue(nativeInterpolationGuard > nativeInterpolationJoin)
    assertTrue(stateSave > nativeInterpolationGuard)
    assertTrue(stateSaveGuard > stateSave)
    assertTrue(stateRead > stateSaveGuard)
    assertTrue(stateReadGuard > stateRead)
    assertTrue(nativeApply > stateReadGuard)
  }

  @Test fun mpvStreamingOptionsKeepADeepReadaheadAndReuseHttpConnections() {
    val mpvView = source("ui/player/MPVView.kt")

    assertTrue(mpvView.contains("PlaybackSession.setOptionString(\"cache\", \"yes\")"))
    assertTrue(mpvView.contains("PlaybackSession.setOptionString(\"demuxer-readahead-secs\", \"20\")"))
    assertFalse(mpvView.contains("PlaybackSession.setOptionString(\"cache\", \"auto\")"))
    // Persistent connections avoid a TCP+TLS handshake per HLS/DASH segment.
    assertFalse(mpvView.contains("http_persistent=0"))
    assertTrue(mpvView.contains("http_persistent=1"))
    assertFalse(mpvView.contains("PlaybackSession.setOptionString(\"demuxer-max-bytes\", \"64MiB\")"))
  }

  @Test fun nativeEngineKeepsThirtySecondsOfBufferForOnlineStreams() {
    val engine = source("ui/player/NativeMedia3Engine.kt")

    assertTrue(engine.contains("30_000"))
    assertTrue(engine.contains("180_000"))
    assertFalse(engine.contains("10_000,\n          120_000"))
  }

  @Test fun thumbnailGenerationUsesHardwareDecodeAndCachesRotationProbes() {
    val repository = source("domain/thumbnail/ThumbnailRepository.kt")

    assertFalse(repository.contains("useHwDec = false"))
    assertTrue(repository.contains("useHwDec = true"))
    assertTrue(repository.contains("rotationCache"))
    // The rotation probe is a second full container parse per file; it must be cached.
    assertTrue(repository.contains("rotationCache.getOrPut(video.uri.toString())"))
  }

  @Test fun loopbackProxiesGetFreshConnectionsWhileRemoteStreamsKeepPersistentOnes() {
    val session = source("ui/player/PlaybackSession.kt")
    val mpvView = source("ui/player/MPVView.kt")

    // The torrent proxy and the stream relays are NanoHTTPD servers on 127.0.0.1 that close
    // every response, so reusing the socket aborts the next range request.
    assertTrue(PlaybackLoopbackLavfOptions.isLoopback("http://127.0.0.1:8099/stream/movie.mkv"))
    assertTrue(PlaybackLoopbackLavfOptions.isLoopback("http://localhost:8099/stream.mkv"))
    assertTrue(PlaybackLoopbackLavfOptions.isLoopback("http://[::1]:8099/stream.mkv"))
    assertFalse(PlaybackLoopbackLavfOptions.isLoopback("https://cdn.example.com/a/b.m3u8"))
    assertFalse(PlaybackLoopbackLavfOptions.isLoopback("magnet:?xt=urn:btih:abc"))
    assertFalse(PlaybackLoopbackLavfOptions.isLoopback("file:///sdcard/movie.mkv"))

    // Loopback drops persistence, remote keeps the handshake-saving default MPVView installs.
    assertTrue(PlaybackLoopbackLavfOptions.LOOPBACK_STREAM.startsWith("http_persistent=0,"))
    assertTrue(PlaybackLoopbackLavfOptions.LOOPBACK_DEMUXER.startsWith("http_persistent=0"))
    assertTrue(PlaybackLoopbackLavfOptions.REMOTE_STREAM.startsWith("http_persistent=1,"))
    // The reconnect family and http_persistent are http protocol options: libavformat applies
    // stream-lavf-o to the opened URLContext. In demuxer-lavf-o mpv rejects each one
    // ("Could not set AVOption reconnect='1'"), so a demuxer set carrying them silently lost the
    // loopback policy and torrent/relay playback stalled on the first reuse.
    val protocolOnlyKeys =
      listOf(
        "reconnect=1",
        "reconnect_on_network_error=1",
        "reconnect_streamed=1",
        "reconnect_delay_max=1",
        "reconnect_max_retries=8",
        "reconnect_delay_total_max=20",
      )
    listOf(PlaybackLoopbackLavfOptions.REMOTE_DEMUXER, PlaybackLoopbackLavfOptions.LOOPBACK_DEMUXER)
      .forEach { demuxer ->
        assertTrue(demuxer, demuxer.startsWith("http_persistent="))
        protocolOnlyKeys.forEach { key ->
          assertFalse("demuxer-lavf-o must not carry $key: $demuxer", demuxer.contains(key))
        }
      }
    // The per-file fallback must be the same value MPVView installs on init, so a switch back
    // to a remote source restores exactly what the instance started with.
    val streamHalves =
      listOf(
        "http_persistent=1,reconnect=1,reconnect_on_network_error=1,reconnect_on_http_error=5xx,",
        "reconnect_streamed=1,reconnect_delay_max=1,reconnect_max_retries=8,",
        "reconnect_delay_total_max=20",
      )
    assertTrue(streamHalves.all { mpvView.contains("\"$it\"") })
    assertEquals(PlaybackLoopbackLavfOptions.REMOTE_STREAM, streamHalves.joinToString(""))
    // MPVView's demuxer set carries the hls/dash persistence flag and nothing else.
    assertTrue(mpvView.contains("\"${PlaybackLoopbackLavfOptions.REMOTE_DEMUXER}\""))

    // Chosen per opened file, right before the load, so a remote -> loopback switch cannot
    // inherit the other connection policy.
    val beforeLoad =
      session.substringBefore("MPVLib.command(\"loadfile\", playableUri, \"replace\", \"-1\", loadOptions)")
    assertTrue(beforeLoad.contains("PlaybackLoopbackLavfOptions.applyFor(playableUri)"))
  }
}
