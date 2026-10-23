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
}
