/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the real Compose-side state with a fake frame clock. */
class BrowserMusicSheetStateTest {
  private class FakeFrameClock : MonotonicFrameClock {
    private var nanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
      yield()
      nanos += 16_000_000L
      return onFrame(nanos)
    }
  }

  private fun <T> withSheet(block: suspend (BrowserMusicSheetState, CoroutineScope) -> T): T =
    runBlocking {
      val scope = CoroutineScope(coroutineContext + SupervisorJob() + FakeFrameClock() + Dispatchers.Unconfined)
      val sheet = BrowserMusicSheetState(scope, flingVelocityPx = 1_000f, keepContentAfterCollapseMs = 0L)
      sheet.travelPx = 1_000f
      sheet.updateAvailability(true)
      try {
        block(sheet, scope)
      } finally {
        scope.cancel()
      }
    }

  @Test
  fun tapOpensAndRestsFullyExpanded() = withSheet { sheet, _ ->
    assertTrue(sheet.expand())
    assertTrue(sheet.isContentComposed)
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Expanded, sheet.phase)
    assertEquals(1f, sheet.progress, 0f)
    assertTrue(sheet.coversScreen)
  }

  @Test
  fun releasedHalfwayDragNeverStaysPartWay() = withSheet { sheet, _ ->
    sheet.beginDrag()
    sheet.dragBy(-420f)
    assertEquals(0.42f, sheet.progress, 1e-4f)
    sheet.release(velocityYPx = 0f)
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Collapsed, sheet.phase)
    assertEquals(0f, sheet.progress, 0f)
  }

  @Test
  fun cancelledPointerGestureStillSettles() = withSheet { sheet, _ ->
    sheet.beginDrag()
    sheet.dragBy(-700f)
    sheet.cancelDrag()
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Expanded, sheet.phase)
  }

  @Test
  fun tapDuringCollapseAnimationReopens() = withSheet { sheet, _ ->
    sheet.expand()
    sheet.awaitSettled()
    sheet.collapse()
    yield()
    yield()
    assertTrue(sheet.progress in 0f..1f)
    assertTrue(sheet.expand())
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Expanded, sheet.phase)
    assertEquals(1f, sheet.progress, 0f)
  }

  @Test
  fun scopeCancelledMidSettleLandsOnTarget() = withSheet { sheet, scope ->
    sheet.expand()
    yield()
    scope.cancel()
    sheet.awaitSettled()
    assertEquals(MusicSheetPhase.Expanded, sheet.phase)
    assertEquals(1f, sheet.progress, 0f)
  }

  @Test
  fun sessionEndingDropsTheSheetImmediately() = withSheet { sheet, _ ->
    sheet.expand()
    sheet.updateAvailability(false)
    assertEquals(MusicSheetPhase.Collapsed, sheet.phase)
    assertFalse(sheet.isContentComposed)
    assertFalse(sheet.expand())
  }

  @Test
  fun backOnACollapsedSheetIsNotConsumed() = withSheet { sheet, _ ->
    assertFalse(sheet.collapse())
  }
}
