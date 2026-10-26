/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSheetMotionTest {
  private val travel = 1_000f

  private fun dragged(to: Float): MusicSheetMotion =
    MusicSheetMotion(flingVelocityPx = 1_000f).apply {
      beginDrag()
      dragBy(-to * travel, travel)
    }

  @Test
  fun dragFollowsFingerAndClampsToBothEnds() {
    val m = dragged(0.3f)
    assertEquals(0.3f, m.progress, 1e-4f)
    m.dragBy(-5_000f, travel)
    assertEquals(1f, m.progress, 0f)
    m.dragBy(9_000f, travel)
    assertEquals(0f, m.progress, 0f)
  }

  @Test
  fun slowReleaseSettlesToTheNearerEnd() {
    assertEquals(1f, dragged(0.6f).release(velocityYPx = 0f), 0f)
    assertEquals(0f, dragged(0.4f).release(velocityYPx = 0f), 0f)
  }

  @Test
  fun flingBeatsPosition() {
    assertEquals(1f, dragged(0.1f).release(velocityYPx = -2_000f), 0f)
    assertEquals(0f, dragged(0.9f).release(velocityYPx = 2_000f), 0f)
  }

  @Test
  fun everyReleaseEndsExactlyAtRestEvenIfTheAnimationIsCutShort() {
    val m = dragged(0.55f)
    m.release(0f)
    assertEquals(MusicSheetPhase.Settling, m.phase)
    m.onAnimationFrame(0.7f)
    // Animation cancelled (composition left, scope cancelled) before reaching 1.
    assertTrue(m.onSettleFinished(m.settleGeneration))
    assertEquals(MusicSheetPhase.Expanded, m.phase)
    assertEquals(1f, m.progress, 0f)
    assertNull(m.target)
  }

  @Test
  fun interruptedDragSettlesByPositionInsteadOfFreezing() {
    val m = dragged(0.35f)
    assertEquals(0f, m.cancelDrag()!!, 0f)
    assertEquals(MusicSheetPhase.Settling, m.phase)
    assertTrue(m.onSettleFinished(m.settleGeneration))
    assertEquals(MusicSheetPhase.Collapsed, m.phase)
    assertEquals(0f, m.progress, 0f)
  }

  @Test
  fun cancelWithoutADragIsIgnored() {
    val m = MusicSheetMotion()
    assertNull(m.cancelDrag())
    assertEquals(MusicSheetPhase.Collapsed, m.phase)
  }

  @Test
  fun grabbingTheSheetMidSettleKeepsItWhereItIsAndInvalidatesTheOldSettle() {
    val m = MusicSheetMotion()
    m.expand()
    val oldGeneration = m.settleGeneration
    m.onAnimationFrame(0.4f)
    m.beginDrag()
    assertEquals(MusicSheetPhase.Dragging, m.phase)
    assertEquals(0.4f, m.progress, 0f)
    // The superseded animation's cleanup must not snap the sheet out from under the finger.
    assertFalse(m.onSettleFinished(oldGeneration))
    assertEquals(MusicSheetPhase.Dragging, m.phase)
    m.onAnimationFrame(0.9f)
    assertEquals("frames are ignored while dragging", 0.4f, m.progress, 0f)
  }

  @Test
  fun tapDuringCollapseTurnsAroundFromTheCurrentPosition() {
    val m = MusicSheetMotion()
    m.snapTo(expanded = true)
    m.collapse()
    val collapseGeneration = m.settleGeneration
    m.onAnimationFrame(0.6f)
    assertEquals(1f, m.expand(), 0f)
    assertEquals("no jump back to either end", 0.6f, m.progress, 0f)
    assertEquals(MusicSheetPhase.Settling, m.phase)
    assertFalse(m.onSettleFinished(collapseGeneration))
    assertTrue(m.onSettleFinished(m.settleGeneration))
    assertEquals(MusicSheetPhase.Expanded, m.phase)
  }

  @Test
  fun repeatedTapsWhileOpeningDoNotRestartOrStall() {
    val m = MusicSheetMotion()
    m.expand()
    m.onAnimationFrame(0.5f)
    m.expand()
    assertEquals(0.5f, m.progress, 0f)
    assertEquals(1f, m.target!!, 0f)
    assertTrue(m.onSettleFinished(m.settleGeneration))
    assertEquals(MusicSheetPhase.Expanded, m.phase)
  }

  @Test
  fun requestingTheCurrentRestingStateNeedsNoAnimation() {
    val m = MusicSheetMotion()
    m.snapTo(expanded = true)
    m.expand()
    assertEquals(MusicSheetPhase.Expanded, m.phase)
    assertNull(m.target)
  }

  @Test
  fun garbageInputCannotPoisonProgress() {
    val m = dragged(0.5f)
    m.dragBy(Float.NaN, travel)
    m.dragBy(10f, 0f)
    assertEquals(0.5f, m.progress, 1e-4f)
    assertEquals(1f, m.release(Float.NaN), 0f)
    m.onAnimationFrame(Float.POSITIVE_INFINITY)
    assertEquals(0.5f, m.progress, 1e-4f)
  }

  @Test
  fun releaseVelocityIsConvertedToProgressUnitsWithUpPositive() {
    assertEquals(2f, MusicSheetMotion.progressVelocity(-2_000f, 1_000f), 1e-4f)
    assertEquals(0f, MusicSheetMotion.progressVelocity(500f, 0f), 0f)
    assertEquals(12f, MusicSheetMotion.progressVelocity(-1e9f, 1f), 0f)
  }
}
