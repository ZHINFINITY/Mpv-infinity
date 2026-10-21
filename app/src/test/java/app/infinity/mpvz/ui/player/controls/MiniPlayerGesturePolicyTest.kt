/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerGesturePolicyTest {
  @Test fun dragMapsDistanceToBoundedProgress() {
    val state = MiniPlayerGesturePolicy.drag(MiniPlayerGestureState(), 240f, 800f)
    assertEquals(0.3f, state.progress, 0.0001f)
    assertEquals(1f, MiniPlayerGesturePolicy.drag(state, 900f, 800f).progress, 0.0001f)
  }

  @Test fun cancelReturnsToFullPresentationState() {
    assertEquals(MiniPlayerGestureState(), MiniPlayerGesturePolicy.cancel())
  }

  @Test fun distanceSettlesToMiniAndBelowThresholdExpands() {
    val mini = MiniPlayerGesturePolicy.settle(MiniPlayerGestureState(progress = 0.7f), 0f)
    assertEquals(MiniPlayerSettle.Mini, mini.settled)
    assertEquals(1f, mini.progress, 0f)
    val full = MiniPlayerGesturePolicy.settle(MiniPlayerGestureState(progress = 0.49f), 0f)
    assertEquals(MiniPlayerSettle.Full, full.settled)
    assertEquals(0f, full.progress, 0f)
  }

  @Test fun velocityCanSettleEitherDirection() {
    assertEquals(MiniPlayerSettle.Mini, MiniPlayerGesturePolicy.settle(MiniPlayerGestureState(0.1f), 1200f).settled)
    assertEquals(MiniPlayerSettle.Full, MiniPlayerGesturePolicy.settle(MiniPlayerGestureState(0.9f), -1200f).settled)
  }

  @Test fun invalidDragInputDoesNotMutatePresentationState() {
    val state = MiniPlayerGestureState(progress = 0.4f)
    assertEquals(state, MiniPlayerGesturePolicy.drag(state, Float.NaN, 800f))
    assertEquals(state, MiniPlayerGesturePolicy.drag(state, 20f, 0f))
    assertEquals(state, MiniPlayerGesturePolicy.drag(state, 20f, Float.NaN))
    assertEquals(state, MiniPlayerGesturePolicy.drag(state, 20f, Float.POSITIVE_INFINITY))
  }

  @Test fun collapseTracksTheActualMiniPlayerGeometryContinuously() {
    val target = MiniPlayerTarget(12f, 1800f, 976f, 80f, 1000f)
    val half = MiniPlayerGesturePolicy.transform(0.5f, 1000f, 2000f, 0f, 0f, target)
    assertEquals(6f, half.x, 0.001f)
    assertEquals(900f, half.y, 0.001f)
    assertEquals(0.988f, half.scaleX, 0.001f)
    assertEquals(0.52f, half.scaleY, 0.001f)
    val end = MiniPlayerGesturePolicy.transform(1f, 1000f, 2000f, 0f, 0f, target)
    assertEquals(12f, end.x, 0.001f)
    assertEquals(1800f, end.y, 0.001f)
    assertEquals(0.04f, end.scaleY, 0.001f)
  }

  @Test fun staleOrientationAnchorIsRejectedAndInvalidPublishCannotReplaceIt() {
    MiniPlayerTransitionAnchor.publish(12f, 1800f, 976f, 80f, 1000f)
    assertEquals(null, MiniPlayerTransitionAnchor.forViewport(2000f))
    val valid = MiniPlayerTransitionAnchor.forViewport(1000f)
    MiniPlayerTransitionAnchor.publish(Float.NaN, 0f, 10f, 10f, 1000f)
    assertEquals(valid, MiniPlayerTransitionAnchor.forViewport(1000f))
  }

  @Test fun missingAnchorAndInvalidViewportHaveSafeTransforms() {
    assertEquals(MiniPlayerTransform(0f, 0f, 1f, 1f),
      MiniPlayerGesturePolicy.transform(1f, Float.NaN, 2000f, 0f, 0f, null))
    val start = MiniPlayerGesturePolicy.transform(Float.NaN, 1000f, 2000f, 0f, 0f, null)
    assertEquals(MiniPlayerTransform(0f, 0f, 1f, 1f), start)
    val end = MiniPlayerGesturePolicy.transform(1f, 1000f, 2000f, 0f, 0f, null)
    assertEquals(0.94f, end.scaleX, 0.001f)
    assertEquals(0.10f, end.scaleY, 0.001f)
  }
}
