/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.controls

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerArtworkFlightTest {
  @After fun clearAnchor() { MiniPlayerTransitionAnchor.invalidate() }

  private val full = MiniPlayerArtworkTarget(50f, 170f, 300f, 300f)
  private val mini = MiniPlayerArtworkTarget(24f, 790f, 48f, 48f)
  private fun root(p: Float) = MiniPlayerGesturePolicy.transform(p, 400f, 900f, 0f, 24f,
    MiniPlayerTarget(12f, 778f, 376f, 72f, 400f, mini))

  private fun assertFlight(p: Float, destination: MiniPlayerArtworkTarget = mini) {
    val parent = root(p)
    val child = MiniPlayerGesturePolicy.artworkFlight(p, parent, 0f, 24f,
      full.left, full.top, full.width, full.height, destination)
    // Compose both top-left-origin layers, not just the child's standalone scale.
    val left = parent.x + (full.left + child.translationX) * parent.scaleX
    val top = 24f + parent.y + (full.top - 24f + child.translationY) * parent.scaleY
    val width = full.width * child.scaleX * parent.scaleX
    val height = full.height * child.scaleY * parent.scaleY
    val side = minOf(destination.width, destination.height)
    assertEquals(full.left + (destination.left - full.left) * p, left, 0.001f)
    assertEquals(full.top + (destination.top - full.top) * p, top, 0.001f)
    assertEquals(full.width + (side - full.width) * p, width, 0.001f)
    assertEquals(width, height, 0.001f)
  }

  @Test fun expandedEndpointIsIdentity() { assertFlight(0f) }
  @Test fun miniEndpointLandsOnMeasuredCover() { assertFlight(1f) }
  @Test fun midpointUsesSameTimelineForPositionAndSquareSize() { assertFlight(0.5f) }
  @Test fun reversedDragRevisitsTheSameGeometry() {
    listOf(0f, 0.2f, 0.8f, 0.4f, 0.1f, 0f).forEach { assertFlight(it) }
  }
  @Test fun rtlRightHandArtworkIsARealDestinationNotALeftInsetConstant() {
    assertFlight(1f, MiniPlayerArtworkTarget(328f, 790f, 48f, 48f))
  }
  @Test fun nonsquareRoundedSlotDoesNotStretchTheCover() {
    assertFlight(1f, MiniPlayerArtworkTarget(24f, 790f, 49f, 48f))
  }
  @Test fun missingArtworkNeverBecomesTheEntireMiniCard() {
    MiniPlayerTransitionAnchor.publish(12f, 778f, 376f, 72f, 400f)
    assertNull(MiniPlayerTransitionAnchor.forViewport(400f)?.artwork)
  }
  @Test fun actualArtworkMustBelongToTheMeasuredMiniSurface() {
    MiniPlayerTransitionAnchor.publish(12f, 778f, 376f, 72f, 400f, 24f, 790f, 48f, 48f)
    assertEquals(mini, MiniPlayerTransitionAnchor.forViewport(400f)?.artwork)
    MiniPlayerTransitionAnchor.publish(12f, 778f, 376f, 72f, 400f, 24f, 10f, 48f, 48f)
    assertNull(MiniPlayerTransitionAnchor.forViewport(400f)?.artwork)
  }
  @Test fun zeroSizeFirstLayoutDoesNotDeleteTheMainActivityAnchor() {
    MiniPlayerTransitionAnchor.publish(12f, 778f, 376f, 72f, 400f)
    assertNull(MiniPlayerTransitionAnchor.forViewport(0f))
    assertNull(MiniPlayerTransitionAnchor.forViewport(Float.NaN))
    assertNotNull(MiniPlayerTransitionAnchor.forViewport(400f))
  }
  @Test fun rotationMismatchInvalidatesTheOldDestination() {
    MiniPlayerTransitionAnchor.publish(12f, 778f, 376f, 72f, 400f)
    assertNull(MiniPlayerTransitionAnchor.forViewport(900f))
    assertNull(MiniPlayerTransitionAnchor.forViewport(400f))
  }
  @Test fun invalidParentCannotProduceNaNLayerProperties() {
    val result = MiniPlayerGesturePolicy.artworkFlight(0.5f,
      MiniPlayerTransform(Float.NaN, 0f, 1f, 1f), 0f, 24f,
      full.left, full.top, full.width, full.height, mini)
    assertEquals(MiniPlayerArtworkFlight(0f, 0f, 1f, 1f), result)
  }
  @Test fun missingCoverUsesFiniteSquareFallbackUnderCompressedRoot() {
    val parent = root(1f)
    val correction = MiniPlayerGesturePolicy.artworkScaleCorrection(1f, parent, 300f, 48f)
    assertEquals(48f, 300f * parent.scaleX * correction.scaleX, 0.001f)
    assertEquals(48f, 300f * parent.scaleY * correction.scaleY, 0.001f)
  }
}
