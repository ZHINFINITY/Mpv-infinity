/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the real Compose-side state with a fake frame clock. */
class BrowserMusicSheetStateTest {
  private class FakeFrameClock(private val manualFrames: Boolean = false) : MonotonicFrameClock {
    private var nanos = 0L
    private val frameSignals = Channel<Unit>(Channel.UNLIMITED)

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
      if (manualFrames) frameSignals.receive() else yield()
      nanos += 16_000_000L
      return onFrame(nanos)
    }

    fun advanceFrame() {
      check(frameSignals.trySend(Unit).isSuccess)
    }
  }

  private fun <T> withSheet(
    keepContentAfterCollapseMs: Long = BrowserMusicSheetState.KEEP_CONTENT_AFTER_COLLAPSE_MS,
    manualFrames: Boolean = false,
    block: suspend (BrowserMusicSheetState, CoroutineScope, FakeFrameClock) -> T,
  ): T =
    runBlocking {
      val clock = FakeFrameClock(manualFrames)
      val scope = CoroutineScope(coroutineContext + SupervisorJob() + clock + Dispatchers.Unconfined)
      val sheet = BrowserMusicSheetState(
        scope,
        flingVelocityPx = 1_000f,
        keepContentAfterCollapseMs = keepContentAfterCollapseMs,
      )
      sheet.travelPx = 1_000f
      sheet.updateAvailability(true)
      try {
        block(sheet, scope, clock)
      } finally {
        scope.cancel()
      }
    }

  private suspend fun driveUntilRested(sheet: BrowserMusicSheetState, clock: FakeFrameClock) {
    var frames = 0
    while (sheet.phase == MusicSheetPhase.Settling) {
      check(frames++ < 200) { "settle did not finish within 200 frames" }
      clock.advanceFrame()
      yield()
    }
    sheet.awaitSettled()
  }

  @Test
  fun tapOpensAndRestsFullyExpanded() = withSheet { sheet, _, _ ->
    assertTrue(sheet.expand())
    assertTrue(sheet.isContentComposed)
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Expanded, sheet.phase)
    assertEquals(1f, sheet.progress, 0f)
    assertTrue(sheet.coversScreen)
  }

  @Test
  fun releasedHalfwayDragNeverStaysPartWay() = withSheet { sheet, _, _ ->
    sheet.beginDrag()
    sheet.dragBy(-420f)
    assertEquals(0.42f, sheet.progress, 1e-4f)
    sheet.release(velocityYPx = 0f)
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Collapsed, sheet.phase)
    assertEquals(0f, sheet.progress, 0f)
  }

  @Test
  fun cancelledPointerGestureStillSettles() = withSheet { sheet, _, _ ->
    sheet.beginDrag()
    sheet.dragBy(-700f)
    sheet.cancelDrag()
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Expanded, sheet.phase)
  }

  @Test
  fun tapDuringCollapseAnimationReopens() = withSheet(manualFrames = true) { sheet, _, clock ->
    sheet.expand()
    driveUntilRested(sheet, clock)
    sheet.collapse()
    assertEquals(MusicSheetPhase.Settling, sheet.phase)
    clock.advanceFrame()
    yield()
    assertEquals(MusicSheetPhase.Settling, sheet.phase)
    assertTrue(sheet.progress > 0f && sheet.progress < 1f)
    assertTrue(sheet.expand())
    driveUntilRested(sheet, clock)
    assertEquals(MusicSheetPhase.Expanded, sheet.phase)
    assertEquals(1f, sheet.progress, 0f)
  }

  @Test
  fun scopeCancelledMidSettleLandsOnTarget() = withSheet(manualFrames = true) { sheet, scope, _ ->
    sheet.expand()
    assertEquals(MusicSheetPhase.Settling, sheet.phase)
    scope.cancel()
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Expanded, sheet.phase)
    assertEquals(1f, sheet.progress, 0f)
  }

  @Test
  fun sessionEndingDropsTheSheetImmediately() = withSheet { sheet, _, _ ->
    sheet.expand()
    sheet.updateAvailability(false)
    assertEquals(MusicSheetPhase.Collapsed, sheet.phase)
    assertFalse(sheet.isContentComposed)
    assertFalse(sheet.expand())
  }

  @Test
  fun prewarmWithoutOpeningReleasesContentAfterTheQuietPeriod() =
    withSheet(keepContentAfterCollapseMs = 100L) { sheet, _, _ ->
      sheet.prewarm()
      assertTrue(sheet.isContentComposed)
      delay(150L)
      assertFalse(sheet.isContentComposed)
    }

  @Test
  fun declaredVideoCannotExposeTheServiceMusicSheet() {
    assertTrue(
      MusicSheetInputPolicy.isAvailable(
        serviceActive = true,
        phase = PlaybackPhase.READY,
        currentItemKind = DeclaredPlaybackMediaKind.AUDIO,
      ),
    )
    assertFalse(
      MusicSheetInputPolicy.isAvailable(
        serviceActive = true,
        phase = PlaybackPhase.READY,
        currentItemKind = DeclaredPlaybackMediaKind.VIDEO,
      ),
    )
    assertFalse(
      MusicSheetInputPolicy.isAvailable(
        serviceActive = false,
        phase = PlaybackPhase.READY,
        currentItemKind = DeclaredPlaybackMediaKind.AUDIO,
      ),
    )
    assertFalse(
      MusicSheetInputPolicy.isAvailable(
        serviceActive = true,
        phase = PlaybackPhase.IDLE,
        currentItemKind = DeclaredPlaybackMediaKind.AUDIO,
      ),
    )
  }

  @Test
  fun backOnACollapsedSheetIsNotConsumed() = withSheet { sheet, _, _ ->
    assertFalse(sheet.collapse())
  }
}
