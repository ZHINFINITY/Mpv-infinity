/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Shared nested-scroll bridge for the full player handoff.
 *
 * The child gets first refusal. Only its positive, unconsumed top-edge remainder is
 * claimed here. Pre-scroll is intentionally limited to an already-started handoff,
 * so a normal LazyColumn swipe is never stolen at the full-player boundary.
 */
internal class MiniPlayerDragController(
  private val state: () -> MiniPlayerGestureState,
  private val setState: (MiniPlayerGestureState) -> Unit,
  private val extentPx: () -> Float,
  private val settle: (Float) -> Unit,
) {
  private var nestedGestureActive = false
  private var settleDispatched = false

  val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      if (source != NestedScrollSource.UserInput || !available.y.isFinite()) return Offset.Zero
      // Pre-scroll is only for an in-progress collapse (including its reversal).
      if (state().progress <= 0f) return Offset.Zero
      return consume(available.y)
    }

    override fun onPostScroll(
      consumed: Offset,
      available: Offset,
      source: NestedScrollSource,
    ): Offset {
      if (source != NestedScrollSource.UserInput || !available.y.isFinite()) return Offset.Zero
      // Positive unconsumed movement means the child is at its top edge.
      if (available.y <= 0f) return Offset.Zero
      return consume(available.y)
    }

    // Settle on release, not after the child's fling animation. A child list used to
    // fling for its whole duration (scrolling underneath the moving page) before
    // onPostFling arrived, which left the half-minimized player frozen meanwhile.
    override suspend fun onPreFling(available: Velocity): Velocity {
      if (!nestedGestureActive || settleDispatched) return Velocity.Zero
      settleDispatched = true
      nestedGestureActive = false
      settle(available.y.takeIf { it.isFinite() } ?: 0f)
      return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
      if (nestedGestureActive && !settleDispatched) {
        settleDispatched = true
        settle((consumed.y + available.y).takeIf { it.isFinite() } ?: 0f)
      }
      nestedGestureActive = false
      return Velocity.Zero
    }
  }

  private fun consume(amount: Float): Offset {
    if (amount == 0f || !extentPx().isFinite() || extentPx() <= 0f) return Offset.Zero
    nestedGestureActive = true
    settleDispatched = false
    setState(MiniPlayerGesturePolicy.drag(state(), amount, extentPx()))
    return Offset(0f, amount)
  }

  fun onRootDragStart() {
    nestedGestureActive = false
    settleDispatched = false
  }

  /** Root cancellation must not erase a nested child handoff awaiting post-fling. */
  fun onRootDragCancel(): Boolean = !nestedGestureActive

  /**
   * Called after every finger has lifted. A nested child whose gesture was cancelled (for
   * example it left composition mid-drag) never flings, so nothing would settle the page.
   * Returns true when the caller must settle; a later fling callback is then ignored.
   */
  fun claimReleaseSettle(): Boolean {
    if (settleDispatched) return false
    settleDispatched = true
    nestedGestureActive = false
    return true
  }
}
