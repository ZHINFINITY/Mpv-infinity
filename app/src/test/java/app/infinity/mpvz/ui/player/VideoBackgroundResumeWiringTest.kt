/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Activity wiring behind the video background resume fix (a6ff792f / 4c0d1ab2):
 * after a long background stay or an OEM Surface teardown, video comes back at the same
 * position with the same tracks instead of a black frame or a restart from 0, on both engines.
 * The thresholds themselves are covered by [PlayerLifecyclePolicyTest].
 */
class VideoBackgroundResumeWiringTest {
  private val activity: String by lazy {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    File(root, "app/infinity/mpvz/ui/player/PlayerActivity.kt").readText()
  }

  private val viewModel: String by lazy {
    val root = File("src/main/java").takeIf { it.exists() } ?: File("app/src/main/java")
    File(root, "app/infinity/mpvz/ui/player/PlayerViewModel.kt").readText()
  }

  private fun body(signature: String): String {
    val start = activity.indexOf(signature)
    assertTrue("missing $signature", start >= 0)
    val end = activity.indexOf("\n  }\n", start)
    assertTrue("unterminated $signature", end > start)
    return activity.substring(start, end)
  }

  private fun assertInOrder(text: String, vararg needles: String) {
    var from = 0
    for (needle in needles) {
      val at = text.indexOf(needle, from)
      assertTrue("expected `$needle` after index $from", at >= 0)
      from = at + needle.length
    }
  }

  @Test
  fun pauseAndStopPersistPositionAndTracksForBothEngines() {
    // onPause saves the shared record (position, fingerprinted tracks, speed) for MPV and Native.
    assertInOrder(
      body("override fun onPause()"),
      "captureForegroundResumePosition()",
      "saveVideoPlaybackState(fileName, immediate = true)",
    )
    // onStop starts the background clock and saves Native once more before Media3 is parked.
    assertInOrder(
      body("override fun onStop()"),
      "playerStoppedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()",
      "captureForegroundResumePosition()",
      "if (isNativeEngineActive() && fileName.isNotBlank()) {",
      "saveVideoPlaybackState(fileName, immediate = true)",
    )
  }

  @Test
  fun foregroundReturnRebuildsAfterLongBackgroundOrReattachesSurface() {
    val restore = body("private fun restoreForegroundVideoAndAmbientIfUnlocked()")
    assertInOrder(
      restore,
      "PlayerLifecyclePolicy.shouldRebuildRendererAfterBackground(stoppedForMs, audioMedia)",
      "val surfaceReady = isNativeEngineActive() || player.isSurfaceReady",
      "PlayerLifecyclePolicy.shouldWaitForSurfaceBeforeRebuild(surfaceReady)",
      "rebuildRendererAfterLongBackground()",
    )
    // Short background stays: reattach the Native surface or re-enable MPV video, then seek back.
    assertInOrder(
      restore,
      "if (isNativeEngineActive()) {",
      "setNativeVideoSurfaceVisible(true)",
      "nativeEngineOrNull()?.let { engine ->",
      "engine.refreshAfterConfigurationChange()",
      "(nativePlayIntent ?: player.playWhenReady) &&",
      "engine.setPlaying(true)",
      "enableVideoAfterBackground()",
      "restoreForegroundResumePositionIfNeeded()",
    )
    // MPV defers the rebuild until Android hands back a Surface (ColorOS black frame).
    assertInOrder(
      activity,
      "player.onSurfaceReady = {",
      "if (pendingRendererRebuildAfterBackground) {",
      "rebuildRendererAfterLongBackground()",
      "restoreForegroundResumePositionIfNeeded()",
    )
  }

  @Test
  fun rebuildReloadsAtCapturedPositionAndKeepsPause() {
    assertInOrder(
      body("private fun rebuildRendererAfterLongBackground()"),
      "val captured = pendingForegroundResumePositionSeconds",
      "if (isNativeEngineActive()) nativePlaybackPositionSeconds()",
      "else PlaybackSession.getPropertyDouble(\"time-pos\")",
      "PlaybackPositionRestoreOverride(",
      "positionSeconds = positionSeconds.takeIf { it.isFinite() && it > 0.0 }",
      "paused = paused",
      "startMediaLoad(",
      "positionRestoreOverride = restore",
    )
  }

  @Test
  fun screenUnlockResumeUsesNativePlaybackIntentInsteadOfMpvPauseState() {
    assertInOrder(
      body("private fun activeEnginePausedState()"),
      "activeEngine = activeEngineMode",
      "nativePlayWhenReady = nativeEngineOrNull()?.currentPlayer?.let",
      "if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) null else player.playWhenReady",
      "mpvPaused = viewModel.paused",
    )
    assertInOrder(
      body("private fun resumePlaybackAfterScreenUnlockIfNeeded()"),
      "if (activeEnginePausedState() == true",
      "if (isNativeEngineActive())",
      "if (requestAudioFocus()) nativeEngineOrNull()?.setPlaying(true)",
      "viewModel.unpause()",
    )
  }

  @Test
  fun missingOrIdleNativeMediaIsRebuiltWhenReturningToForeground() {
    val restore = body("private fun restoreForegroundVideoAndAmbientIfUnlocked()")
    assertInOrder(
      restore,
      "val nativeEngine = nativeEngineOrNull()",
      "val nativePlayer = nativeEngine?.currentPlayer",
      "PlayerLifecyclePolicy.shouldRecoverNativePlayback(",
      "hasCurrentMediaItem = nativePlayer?.currentMediaItem != null",
      "playbackIsIdle = nativePlayer?.playbackState == androidx.media3.common.Player.STATE_IDLE",
      "mediaLoadInProgress = mediaLoadJob?.isActive == true",
      "val hasPlayableSource = !currentPlayableUri.isNullOrBlank() || !intent?.dataString.isNullOrBlank()",
      "if (shouldRecoverNative && hasPlayableSource)",
      "rebuildRendererAfterLongBackground()",
      "return true",
    )
  }

  @Test
  fun nativeRecoveryUsesCapturedPlayIntentAndIntentUriFallback() {
    assertTrue(
      body("private fun captureForegroundResumePosition()")
        .contains("pendingNativeForegroundPlayIntent = nativeEngineOrNull()?.currentPlayer?.let"),
    )
    assertTrue(
      body("private fun restoreForegroundVideoAndAmbientIfUnlocked()")
        .contains("(nativePlayIntent ?: player.playWhenReady)"),
    )
    assertInOrder(
      body("private fun rebuildRendererAfterLongBackground()"),
      "val playable = currentPlayableUri?.takeIf { it.isNotBlank() } ?: intent?.dataString?.takeIf { it.isNotBlank() }",
      "val nativePlayIntent = pendingNativeForegroundPlayIntent",
      "pendingNativeForegroundPlayIntent = null",
      "val nativePaused = nativePlayIntent?.let { wasPlaying -> !wasPlaying } ?: activeEnginePausedState()",
      "val paused = if (isNativeEngineActive()) nativePaused ?: mpvPaused else mpvPaused",
      "startMediaLoad(",
    )
  }

  @Test
  fun audioFocusAndSystemControlsFollowTheActiveEngine() {
    val focus = activity.substringAfter("private val audioFocusChangeHandler =").substringBefore("private val audioFocusChangeListener")
    assertTrue(focus.contains("activeEnginePausedState()"))
    assertTrue(focus.contains("setActiveEnginePlaying(false)"))
    assertTrue(focus.contains("if (shouldResume) setActiveEnginePlaying(true)"))
    assertTrue(!focus.contains("PlaybackSession.setPropertyBoolean(\"pause\","))
    assertInOrder(
      body("private fun setActiveEnginePlaying(playing: Boolean)"),
      "if (isNativeEngineActive())",
      "nativeEngineOrNull()?.setPlaying(playing)",
      "PlaybackSession.setPropertyBoolean(\"pause\", !playing)",
    )

    val pause = viewModel.substringAfter("fun pause()").substringBefore("fun unpause()")
    val unpause = viewModel.substringAfter("fun unpause()").substringBefore("// ==================== UI Control")
    assertTrue(pause.contains("if (host.isNativeEngineActive())"))
    assertTrue(pause.contains("host.nativePause()"))
    assertTrue(unpause.contains("if (host.isNativeEngineActive())"))
    assertTrue(unpause.contains("host.nativeUnpause()"))
  }

  @Test
  fun forcedPausePathsUseTheActiveRenderer() {
    val noisy = activity.substringAfter("private val noisyReceiver =").substringBefore("private val screenStateReceiver")
    assertTrue(noisy.contains("pauseActiveEngine()"))
    assertInOrder(
      body("private fun pauseActiveEngine()"),
      "if (isNativeEngineActive())",
      "engine.setPlaying(false)",
      "viewModel.pause()",
    )
    assertInOrder(
      body("override fun onStop()"),
      "rememberResumeAfterUnlockBeforeForcedPause()",
      "pauseActiveEngine()",
      "if (isDeviceScreenOffOrLocked() && !isBackgroundPlaybackEnabled())",
      "rememberResumeAfterUnlockBeforeForcedPause()",
      "pauseActiveEngine()",
      "else if (!isBackgroundPlaybackSessionActive && (isUserFinishing || isFinishing))",
      "pauseActiveEngine()",
    )
  }

  @Test
  fun resumeSeekUsesTheActiveEngine() {
    assertInOrder(
      body("private fun restoreForegroundResumePositionIfNeeded()"),
      "if (currentPlaybackIsAudioMedia()",
      "if (isNativeEngineActive()) {",
      "nativeEngineOrNull()?.seekTo((seconds * 1000.0).toLong())",
      "if (!player.isSurfaceReady) return",
      "PlaybackSession.setPropertyDouble(\"time-pos\", seconds)",
      "pendingForegroundResumePositionSeconds = null",
    )
  }

  @Test
  fun audioAndSubtitleTrackRoutingFollowsTheActiveEngine() {
    val trackFlows = viewModel.substringAfter("val subtitleTracks: StateFlow").substringBefore("val videoQualityTracks:")
    assertTrue(trackFlows.contains("nativeExternalSubtitleTracks, activePlaybackEngine"))
    assertTrue(trackFlows.contains("nativeAudioTracks, activePlaybackEngine"))
    assertTrue(trackFlows.contains("PlaybackTrackSourcePolicy.forActiveEngine"))
    assertTrue(!trackFlows.contains("decoderPreferences.playbackEngine"))

    val audioSelection = viewModel.substringAfter("fun selectAudioTrack(").substringBefore("private fun currentItemRequiresYtdlp")
    assertTrue(audioSelection.contains("activePlaybackEngine.value == PlaybackEngineMode.NATIVE"))
    val subtitleSelection = viewModel.substringAfter("fun toggleSubtitle(").substringBefore("fun isSubtitleSelected(")
    assertTrue(subtitleSelection.contains("activePlaybackEngine.value == PlaybackEngineMode.NATIVE"))
  }

  @Test
  fun savedTracksAreRestoredEvenWhenAPositionOverrideIsUsed() {
    // The rebuild passes a position override; the fingerprinted track selections must still
    // be queued (MPV) and applied (Native) so audio/subtitle choices survive the reload.
    val load = body("private suspend fun loadVideoPlaybackState(")
    assertInOrder(
      load,
      "pendingSharedAudioSelection = state?.audioSelection",
      "pendingSharedSubtitleSelection = state?.subtitleSelection",
    )
    assertTrue(!load.contains("if (positionRestoreOverride == null) pendingSharedAudioSelection"))
    assertTrue(
      body("private suspend fun applyNativePlaybackState(")
        .contains("nativeEngineOrNull()?.restoreTrackSelections(state?.audioSelection, state?.subtitleSelection)"),
    )
  }
}
