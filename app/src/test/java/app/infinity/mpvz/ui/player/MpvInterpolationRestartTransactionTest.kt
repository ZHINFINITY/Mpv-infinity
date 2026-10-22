/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MpvInterpolationRestartTransactionTest {
  private fun snapshot(paused: Boolean = false) = MpvInterpolationRestartState(
    mediaId = "media:current-not-next",
    sessionGeneration = 7L,
    positionSeconds = 123.456789,
    paused = paused,
    tracks = mapOf("aid" to "selected:Japanese", "sid" to "disabled", "secondary-sid" to "selected:external"),
    doubleProperties = mapOf("speed" to 1.25, "audio-delay" to 0.2, "video-zoom" to 0.4),
    stringProperties = mapOf("video-aspect-override" to "1.777777", "ab-loop-a" to "120"),
    muted = true,
    externalSubtitles = listOf("content://subtitles/selected.srt"),
  )

  private class Host : MpvInterpolationRestartTransaction.Host {
    val calls = mutableListOf<String>()
    var current = true
    var currentGeneration = 7L
    var committedGeneration: Long? = null
    var freezeAcknowledged = true
    var restoreAcknowledged = true
    var returnedGeneration: Long? = 42L
    var stateAfterLoad: MpvInterpolationRestartState? = null
    var restoredState: MpvInterpolationRestartState? = null
    var onFreeze: () -> Unit = {}
    var onLoad: () -> Unit = {}
    var onRestore: () -> Unit = {}
    var actualConfiguration = "old"
    var requestedConfiguration = "interpolation=yes,target-fps=120"
    override fun isCurrent(state: MpvInterpolationRestartState) = current &&
      currentGeneration == (committedGeneration ?: state.sessionGeneration)
    override suspend fun freeze(state: MpvInterpolationRestartState): Boolean {
      calls += "freeze"
      onFreeze()
      return freezeAcknowledged
    }
    override suspend fun reconfigureAndLoad(state: MpvInterpolationRestartState): Long? {
      calls += "configure-and-load"
      stateAfterLoad = state
      actualConfiguration = requestedConfiguration
      committedGeneration = returnedGeneration
      returnedGeneration?.let { currentGeneration = it }
      onLoad()
      return returnedGeneration
    }
    override suspend fun awaitAndRestore(state: MpvInterpolationRestartState, generation: Long): Boolean {
      assertEquals(42L, generation)
      calls += "await-ready-and-restore"
      restoredState = state
      onRestore()
      return restoreAcknowledged
    }
  }

  @Test fun enabledAndTargetFpsAffectNewConfigurationBeforeSuccess() = runBlocking {
    val host = Host()
    assertTrue(MpvInterpolationRestartTransaction.run(snapshot(), host))
    assertEquals("interpolation=yes,target-fps=120", host.actualConfiguration)
    assertEquals(listOf("freeze", "configure-and-load", "await-ready-and-restore"), host.calls)
    host.requestedConfiguration = "interpolation=no,target-fps=60"
    assertTrue(MpvInterpolationRestartTransaction.run(snapshot().copy(sessionGeneration = 42L), host))
    assertEquals("interpolation=no,target-fps=60", host.actualConfiguration)
  }
  @Test fun exactLivePositionPlayingStateAndTracksSurviveWithoutRounding() = runBlocking {
    val before = snapshot(paused = false)
    val host = Host()
    assertTrue(MpvInterpolationRestartTransaction.run(before, host))
    assertEquals(123.456789, host.restoredState!!.positionSeconds, 0.0)
    assertFalse(host.restoredState!!.paused)
    assertEquals(before.tracks, host.restoredState!!.tracks)
    assertEquals(before.doubleProperties, host.restoredState!!.doubleProperties)
    assertEquals(before.externalSubtitles, host.restoredState!!.externalSubtitles)
  }
  @Test fun pausedPlaybackStaysPausedAndPreservesMuteAspectLoopsAndSubtitlesOff() = runBlocking {
    val before = snapshot(paused = true)
    val host = Host()
    assertTrue(MpvInterpolationRestartTransaction.run(before, host))
    assertTrue(host.restoredState!!.paused)
    assertTrue(host.restoredState!!.muted)
    assertEquals(before.stringProperties, host.restoredState!!.stringProperties)
    assertEquals("disabled", host.restoredState!!.tracks["sid"])
    assertEquals(before, host.stateAfterLoad)
  }
  @Test fun ownerOrMediaChangedBeforeRequestCannotTouchCore() = runBlocking {
    val host = Host().apply { current = false }
    assertFalse(MpvInterpolationRestartTransaction.run(snapshot(), host))
    assertTrue(host.calls.isEmpty())
  }
  @Test fun mediaChangedWhileFreezingCannotReconfigureWrongItem() = runBlocking {
    val host = Host().apply { onFreeze = { current = false } }
    assertFalse(MpvInterpolationRestartTransaction.run(snapshot(), host))
    assertEquals(listOf("freeze"), host.calls)
  }
  @Test fun sameItemNewerGenerationWhileFreezingCannotBeTornDown() = runBlocking {
    val host = Host().apply { onFreeze = { currentGeneration = 8L } }
    assertFalse(MpvInterpolationRestartTransaction.run(snapshot(), host))
    assertEquals(listOf("freeze"), host.calls)
  }
  @Test fun mediaChangedAfterLoadCannotApplyOldPositionOrTracksToNewItem() = runBlocking {
    val host = Host().apply { onLoad = { current = false } }
    assertFalse(MpvInterpolationRestartTransaction.run(snapshot(), host))
    assertNull(host.restoredState)
  }
  @Test fun staleRestoreNeverReportsSuccessfulApply() = runBlocking {
    val host = Host().apply { onRestore = { current = false } }
    assertFalse(MpvInterpolationRestartTransaction.run(snapshot(), host))
  }
  @Test fun failedPauseAcknowledgementDoesNotTearDownPlayback() = runBlocking {
    val host = Host().apply { freezeAcknowledged = false }
    assertFalse(MpvInterpolationRestartTransaction.run(snapshot(), host))
    assertEquals(listOf("freeze"), host.calls)
  }
  @Test fun failedLoadCannotRunTrackRestoration() = runBlocking {
    val host = Host().apply { returnedGeneration = null }
    assertFalse(MpvInterpolationRestartTransaction.run(snapshot(), host))
    assertNull(host.restoredState)
  }
  @Test fun failedRestoreAcknowledgementNeverClaimsSuccess() = runBlocking {
    val host = Host().apply { restoreAcknowledged = false }
    assertFalse(MpvInterpolationRestartTransaction.run(snapshot(), host))
  }
  @Test fun missingMediaOrNonFinitePositionCannotStartARestart() = runBlocking {
    for (invalid in listOf(snapshot().copy(mediaId = ""), snapshot().copy(positionSeconds = Double.NaN),
      snapshot().copy(positionSeconds = Double.POSITIVE_INFINITY), snapshot().copy(positionSeconds = -1.0),
      snapshot().copy(sessionGeneration = 0L))) {
      val host = Host()
      assertFalse(MpvInterpolationRestartTransaction.run(invalid, host))
      assertTrue(host.calls.isEmpty())
    }
  }
}
