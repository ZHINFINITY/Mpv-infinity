/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAspectSurfaceDiagnosticsContractTest {
  private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "app/src/main/java/app/infinity/mpvz").isDirectory }
  private fun source(path: String) = File(root, "app/src/main/java/app/infinity/mpvz/$path").readText()

  @Test fun mpvAspectModesStayLivePropertyWritesWithoutSurfaceRebindOrFlowMutation() {
    val viewModel = source("ui/player/PlayerViewModel.kt")
    val mpvView = source("ui/player/MPVView.kt")
    val transition = viewModel.substringAfter("fun changeVideoAspect(")
      .substringBefore("fun setCustomAspectRatio(")
    val inactiveFlowOptions = mpvView.substringAfter("if (!MpvConfigOverridePolicy.isOwnedByMpvConf(\"interpolation\"))")
      .substringBefore("if (!MpvConfigOverridePolicy.isOwnedByMpvConf(\"video-sync\"))")

    assertTrue(transition.contains("video-aspect-override"))
    assertTrue(transition.contains("panscan"))
    assertFalse(transition.contains("bindSurface("))
    assertFalse(transition.contains("unbindSurface("))
    assertFalse(transition.contains("initializeSession("))
    assertFalse(transition.contains("mpvFlowFrameInterpolation"))
    assertTrue(inactiveFlowOptions.contains("if (mpvFlowFrameInterpolationEnabled) \"yes\" else \"no\""))
  }

  @Test fun debugTraceCorrelatesAspectPropertiesWithSurfaceLifecycle() {
    val activity = source("ui/player/PlayerActivity.kt")
    val mpvView = source("ui/player/MPVView.kt")
    val aspectTrace = activity.substringAfter("override fun onVideoAspectChanged()")
      .substringBefore("private var interpolationControlRequestGeneration")
    val surfaceChange = mpvView.substringAfter("override fun surfaceChanged(")
      .substringBefore("override fun surfaceCreated(")
    val surfaceCreate = mpvView.substringAfter("override fun surfaceCreated(")
      .substringBefore("override fun surfaceDestroyed(")
    val surfaceDestroy = mpvView.substringAfter("override fun surfaceDestroyed(")
      .substringBefore("override fun onDetachedFromWindow(")

    for (field in listOf("MPV_ASPECT_DIAGNOSTIC", "video-aspect-override", "panscan", "interpolation", "video-sync", "gpuApi", "gpuContext", "ambient")) {
      assertTrue("Missing aspect trace field: $field", aspectTrace.contains(field))
    }
    assertTrue(surfaceChange.contains("event=surface_changed"))
    assertTrue(surfaceChange.contains("PlaybackSession.resizeSurface"))
    assertTrue(surfaceCreate.contains("event=surface_created"))
    assertTrue(surfaceDestroy.contains("event=surface_destroyed"))
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
    val interpolationJoin = activity.indexOf("mpvInterpolationRestartJob?.join()")
    val requestCapture = activity.indexOf("val handoffRequestGeneration = mediaRequestGeneration", interpolationJoin)
    val stateSave = activity.indexOf("saveVideoPlaybackState(fileName, immediate = true)?.join()", requestCapture)
    val stateRead = activity.indexOf("playbackStateRepository.getVideoDataByTitle", stateSave)
    val nativeApply =
      activity.indexOf("applyNativePlaybackState(handoffState, handoffRequestGeneration)", stateRead)

    assertTrue(interpolationJoin >= 0)
    assertTrue(requestCapture > interpolationJoin)
    assertTrue(stateSave > requestCapture)
    assertTrue(stateRead > stateSave)
    assertTrue(nativeApply > stateRead)
  }
}
