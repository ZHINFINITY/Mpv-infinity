/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player.controls

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerDragControllerTest {
  private class Harness {
    var state = MiniPlayerGestureState()
    var settleCount = 0
    var lastVelocity = 0f
    val controller = MiniPlayerDragController(
      state = { state },
      setState = { state = it },
      extentPx = { 800f },
      settle = { settleCount++; lastVelocity = it },
    )
  }

  @Test
  fun childConsumesNormalSwipeAndOnlyTopRemainderCollapses() {
    val h = Harness()
    val connection = h.controller.nestedScrollConnection
    assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, 48f), NestedScrollSource.UserInput))
    assertEquals(Offset.Zero, connection.onPostScroll(Offset(0f, 48f), Offset.Zero, NestedScrollSource.UserInput))
    val claimed = connection.onPostScroll(Offset.Zero, Offset(0f, 48f), NestedScrollSource.UserInput)
    assertEquals(Offset(0f, 48f), claimed)
    assertEquals(0.06f, h.state.progress, 0.0001f)
  }

  @Test
  fun rootCancelDoesNotDropNestedGestureAndPostFlingSettlesOnce() = runBlocking {
    val h = Harness()
    val connection = h.controller.nestedScrollConnection
    connection.onPostScroll(Offset.Zero, Offset(0f, 40f), NestedScrollSource.UserInput)
    assertFalse(h.controller.onRootDragCancel())
    connection.onPostFling(Velocity.Zero, Velocity(0f, 1200f))
    connection.onPostFling(Velocity.Zero, Velocity(0f, 1200f))
    assertEquals(1, h.settleCount)
    assertEquals(1200f, h.lastVelocity, 0f)
    assertTrue(h.state.progress > 0f)
  }

  @Test
  fun preFlingSettlesOnReleaseAndConsumesChildFling() = runBlocking {
    val h = Harness()
    val connection = h.controller.nestedScrollConnection
    connection.onPostScroll(Offset.Zero, Offset(0f, 120f), NestedScrollSource.UserInput)
    val consumed = connection.onPreFling(Velocity(0f, 900f))
    assertEquals(Velocity(0f, 900f), consumed)
    assertEquals(1, h.settleCount)
    assertEquals(900f, h.lastVelocity, 0f)
    // The post-fling that follows the same gesture must not settle a second time.
    connection.onPostFling(Velocity.Zero, Velocity.Zero)
    assertEquals(1, h.settleCount)
  }

  @Test
  fun preFlingLeavesNormalListFlingAlone() = runBlocking {
    val h = Harness()
    assertEquals(Velocity.Zero, h.controller.nestedScrollConnection.onPreFling(Velocity(0f, -900f)))
    assertEquals(0, h.settleCount)
  }

  @Test
  fun releaseClaimSettlesADroppedNestedGestureOnlyOnce() = runBlocking {
    val h = Harness()
    val connection = h.controller.nestedScrollConnection
    connection.onPostScroll(Offset.Zero, Offset(0f, 200f), NestedScrollSource.UserInput)
    assertTrue(h.controller.claimReleaseSettle())
    assertFalse(h.controller.claimReleaseSettle())
    connection.onPostFling(Velocity.Zero, Velocity(0f, 500f))
    assertEquals(0, h.settleCount)
  }

  @Test
  fun onlyAPartlyMinimizedPageWithoutSettleNeedsReleaseSettle() {
    assertTrue(MusicMinimizeTransitionPolicy.needsReleaseSettle(false, 0.4f))
    assertFalse(MusicMinimizeTransitionPolicy.needsReleaseSettle(true, 0.4f))
    assertFalse(MusicMinimizeTransitionPolicy.needsReleaseSettle(false, 0f))
    assertFalse(MusicMinimizeTransitionPolicy.needsReleaseSettle(false, 1f))
    assertFalse(MusicMinimizeTransitionPolicy.needsReleaseSettle(false, Float.NaN))
  }
}
