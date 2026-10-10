package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowGeometryTest {
  @Test
  fun sharedFlowPyramidGeometryAndSearchConstantsStayAligned() {
    assertEquals(6, MEDIA3_FLOW_GRID_STEP)
    assertEquals(4, MEDIA3_FLOW_COARSE_BLOCK_SIZE)
    assertEquals(3, MEDIA3_FLOW_COARSE_GRID_STEP)
    assertEquals(4, MEDIA3_FLOW_COARSE_SEARCH_RADIUS)
    assertEquals(2, MEDIA3_FLOW_FINE_SEARCH_RADIUS)
    assertEquals(2f, MEDIA3_FLOW_FINE_PRIOR_SCALE)
    assertEquals(Media3FlowMotionSize(200, 113), Media3FlowGeometry.coarseMotionSize(400, 225))
    assertEquals(Media3FlowMotionSize(100, 57), Media3FlowGeometry.quarterMotionSize(400, 225))
    assertEquals(Media3FlowGridSize(66, 37), Media3FlowGeometry.motionGridSize(400, 225))
    assertEquals(
      Media3FlowGridSize(16, 9),
      Media3FlowGeometry.motionGridSize(100, 57, MEDIA3_FLOW_QUARTER_BLOCK_SIZE, MEDIA3_FLOW_QUARTER_GRID_STEP),
    )
    assertEquals(0.25f, Media3FlowGeometry.priorCellScale(3, 6), 0f)
    assertEquals(1f, Media3FlowGeometry.priorCellScale(6, 3), 0f)
  }

  @Test
  fun coarseAndFinePatchOriginsStayAlignedAtOddAndEvenBoundaries() {
    val dimensions = listOf(16 to 16, 17 to 31, 400 to 225, 640 to 360, 721 to 359)
    for ((width, height) in dimensions) {
      val grid = Media3FlowGeometry.motionGridSize(width, height)
      val coarseWidth = (width + 1) / 2
      val coarseHeight = (height + 1) / 2
      for (cellX in 0 until grid.width) {
        val fineOrigin = (cellX * MEDIA3_FLOW_GRID_STEP).coerceAtMost(width - MEDIA3_FLOW_BLOCK_SIZE)
        val coarseOrigin = (cellX * MEDIA3_FLOW_COARSE_GRID_STEP)
          .coerceAtMost(coarseWidth - MEDIA3_FLOW_COARSE_BLOCK_SIZE) * 2
        assertEquals("x anchor mismatch at ${width}x${height}, cell $cellX", fineOrigin, coarseOrigin)
      }
      for (cellY in 0 until grid.height) {
        val fineOrigin = (cellY * MEDIA3_FLOW_GRID_STEP).coerceAtMost(height - MEDIA3_FLOW_BLOCK_SIZE)
        val coarseOrigin = (cellY * MEDIA3_FLOW_COARSE_GRID_STEP)
          .coerceAtMost(coarseHeight - MEDIA3_FLOW_COARSE_BLOCK_SIZE) * 2
        assertEquals("y anchor mismatch at ${width}x${height}, cell $cellY", fineOrigin, coarseOrigin)
      }
    }
  }

  @Test
  fun parabolicMotionRefinementRecoversFractionalOffsetsAndRejectsUnstableCosts() {
    assertEquals(0.25f, Media3FlowGeometry.parabolicSubpixelOffset(1.5625f, 0.0625f, 0.5625f), 0.0001f)
    assertEquals(-0.25f, Media3FlowGeometry.parabolicSubpixelOffset(0.5625f, 0.0625f, 1.5625f), 0.0001f)
    assertEquals(0f, Media3FlowGeometry.parabolicSubpixelOffset(0.2f, 0.2f, 0.2f), 0f)
    assertEquals(0f, Media3FlowGeometry.parabolicSubpixelOffset(0.2f, 0.3f, 0.1f), 0f)
    assertEquals(0f, Media3FlowGeometry.parabolicSubpixelOffset(Float.NaN, 0.1f, 0.2f), 0f)
  }

  @Test
  fun appearanceGuidanceKeepsForegroundAndBackgroundMotionLayersSeparate() {
    val candidates = listOf(
      Media3FlowGuideSample(Media3FlowVector(12f, 0f), 0.8f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(12f, 0f), 0.8f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(0f, 0f), 0.2f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(0f, 0f), 0.2f, 0.25f),
    )
    val foreground = Media3FlowGeometry.edgeAwareVectorSample(0.8f, candidates)
    val background = Media3FlowGeometry.edgeAwareVectorSample(0.2f, candidates)
    assertTrue("Foreground pixels should retain the foreground displacement", foreground.x > 9f)
    assertTrue("Background pixels should retain the background displacement", background.x < 3f)
  }

  @Test
  fun appearanceGuidancePreservesBilinearSamplingWithinOneMotionLayer() {
    val blended = Media3FlowGeometry.edgeAwareVectorSample(
      queryGuide = 0.4f,
      candidates = listOf(
        Media3FlowGuideSample(Media3FlowVector(0f, 0f), 0.4f, 0.5625f),
        Media3FlowGuideSample(Media3FlowVector(4f, 0f), 0.4f, 0.1875f),
        Media3FlowGuideSample(Media3FlowVector(8f, 0f), 0.4f, 0.1875f),
        Media3FlowGuideSample(Media3FlowVector(12f, 0f), 0.4f, 0.0625f),
      ),
    )
    assertEquals(3f, blended.x, 0.0001f)
    assertEquals(0f, blended.y, 0f)
  }

  @Test
  fun foregroundToBackgroundTransitionIsMonotoneAndMotionRemainsBounded() {
    val candidates = listOf(
      Media3FlowGuideSample(Media3FlowVector(12f, -2f), 0.8f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(12f, -2f), 0.8f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(0f, 3f), 0.2f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(0f, 3f), 0.2f, 0.25f),
    )
    val transitions = (0..12).map { index ->
      Media3FlowGeometry.edgeAwareVectorSample(0.2f + index / 12f * 0.6f, candidates)
    }
    assertTrue(transitions.zipWithNext().all { (left, right) -> left.x <= right.x })
    assertTrue(transitions.all { it.x in 0f..12f && it.y in -2f..3f })
    assertTrue(transitions.zipWithNext().all { (left, right) -> right.x - left.x < 3f })
  }

  @Test
  fun crossingOppositeMotionLayersDoNotAverageIntoAStationaryVector() {
    val candidates = listOf(
      Media3FlowGuideSample(Media3FlowVector(16f, 1f), 0.85f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(16f, 1f), 0.85f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(-16f, -1f), 0.15f, 0.25f),
      Media3FlowGuideSample(Media3FlowVector(-16f, -1f), 0.15f, 0.25f),
    )
    val forwardLayer = Media3FlowGeometry.edgeAwareVectorSample(0.85f, candidates)
    val reverseLayer = Media3FlowGeometry.edgeAwareVectorSample(0.15f, candidates)
    assertTrue("The foreground motion must retain its positive displacement", forwardLayer.x > 12f)
    assertTrue("The crossing background/object must retain its negative displacement", reverseLayer.x < -12f)
  }

  @Test
  fun endpointVisibilityPreservesNormalTimingAndSelectsTheReliableSide() {
    assertEquals(0.25f, Media3FlowGeometry.sourceVisibilityBlendAlpha(0.25f, 1f, 1f)!!, 0.0001f)
    assertEquals(1f, Media3FlowGeometry.sourceVisibilityBlendAlpha(0.5f, 0.1f, 1f)!!, 0f)
    assertEquals(0f, Media3FlowGeometry.sourceVisibilityBlendAlpha(0.5f, 1f, 0.1f)!!, 0f)
    assertNull(Media3FlowGeometry.sourceVisibilityBlendAlpha(0.5f, 0.14f, 0.14f))
    assertEquals(0f, Media3FlowGeometry.sourceVisibilityBlendAlpha(0f, 0.8f, 0.1f)!!, 0f)
    assertEquals(1f, Media3FlowGeometry.sourceVisibilityBlendAlpha(1f, 0.1f, 0.8f)!!, 0f)
  }

  @Test
  fun processingCapsPreserveAspectRatioForLandscapePortraitAndSmallerSources() {
    assertEquals(Media3FlowMotionSize(480, 270), Media3FlowGeometry.motionSize(1920, 1080, 480))
    assertEquals(Media3FlowMotionSize(640, 360), Media3FlowGeometry.motionSize(1920, 1080, 640))
    assertEquals(Media3FlowMotionSize(270, 480), Media3FlowGeometry.motionSize(1080, 1920, 480))
    assertEquals(Media3FlowMotionSize(320, 180), Media3FlowGeometry.motionSize(320, 180, 480))
  }

  @Test
  fun motionGridUsesOnlyValidRegularBlockOrigins() {
    val grid = Media3FlowGeometry.motionGridSize(640, 360)
    assertEquals(Media3FlowGridSize(106, 59), grid)
    assertEquals(630, (grid.width - 1) * MEDIA3_FLOW_GRID_STEP)
    assertEquals(348, (grid.height - 1) * MEDIA3_FLOW_GRID_STEP)
    assertTrue((grid.width - 1) * MEDIA3_FLOW_GRID_STEP <= 640 - MEDIA3_FLOW_BLOCK_SIZE)
    assertTrue((grid.height - 1) * MEDIA3_FLOW_GRID_STEP <= 360 - MEDIA3_FLOW_BLOCK_SIZE)
    assertEquals(Media3FlowGridSize(105, 60), Media3FlowGeometry.motionGridSize(632, 362))
  }

  @Test
  fun targetTimeBidirectionalOffsetsUseBothFlowDirectionsAndHitEndpoints() {
    val forward = Media3FlowVector(9f, -2f)
    val backward = Media3FlowVector(-7f, 3f)
    val atFrame0 = Media3FlowGeometry.targetTimeEndpointOffsets(0f, forward, backward)
    val atFrame1 = Media3FlowGeometry.targetTimeEndpointOffsets(1f, forward, backward)
    assertEquals(0f, atFrame0.frame0.x, 0.0001f)
    assertEquals(0f, atFrame0.frame0.y, 0.0001f)
    assertEquals(forward, atFrame0.frame1)
    assertEquals(backward, atFrame1.frame0)
    assertEquals(0f, atFrame1.frame1.x, 0.0001f)
    assertEquals(0f, atFrame1.frame1.y, 0.0001f)
    val middle = Media3FlowGeometry.targetTimeEndpointOffsets(0.5f, Media3FlowVector(10f, 0f), Media3FlowVector(-6f, 0f))
    assertEquals(-4f, middle.frame0.x, 0.0001f)
    assertEquals(4f, middle.frame1.x, 0.0001f)
  }

  @Test
  fun fitAndCropBlitGeometryKeepsTheSourceCentered() {
    assertEquals(
      Media3FlowBlitGeometry(200, 0, 1200, 900, 1f, 1f, 0f, 0f),
      Media3FlowGeometry.blitGeometry(800, 600, 1600, 900, VideoAspect.Fit),
    )
    assertEquals(
      Media3FlowBlitGeometry(0, 200, 800, 400, 1f, 1f, 0f, 0f),
      Media3FlowGeometry.blitGeometry(1600, 800, 800, 800, VideoAspect.Fit),
    )
    assertEquals(
      Media3FlowBlitGeometry(0, 0, 1600, 900, 1f, 0.75f, 0f, 0.125f),
      Media3FlowGeometry.blitGeometry(800, 600, 1600, 900, VideoAspect.Crop),
    )
    assertEquals(
      Media3FlowBlitGeometry(0, 0, 800, 800, 0.5f, 1f, 0.25f, 0f),
      Media3FlowGeometry.blitGeometry(1600, 800, 800, 800, VideoAspect.Crop),
    )
  }
}
