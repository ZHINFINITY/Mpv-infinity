package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowGeometryTest {
  @Test
  fun motionSearchUsesFourPixelCoarseStepAndEightPixelMaximumRange() {
    assertEquals(4, MEDIA3_FLOW_COARSE_SEARCH_STEP)
    assertEquals(8, MEDIA3_FLOW_EXTENDED_SEARCH_RADIUS)
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
  fun sourceVisibilityPreservesNormalTimingButSelectsTheReliableOcclusionSide() {
    assertEquals(0.25f, Media3FlowGeometry.sourceVisibilityBlendAlpha(0.25f, 1f, 1f)!!, 0.0001f)
    assertEquals(1f, Media3FlowGeometry.sourceVisibilityBlendAlpha(0.5f, 0.1f, 1f)!!, 0f)
    assertEquals(0f, Media3FlowGeometry.sourceVisibilityBlendAlpha(0.5f, 1f, 0.1f)!!, 0f)
    assertNull(Media3FlowGeometry.sourceVisibilityBlendAlpha(0.5f, 0.14f, 0.14f))
  }

  @Test
  fun fullHdSourceUsesReducedMotionGrid() {
    assertEquals(Media3FlowMotionSize(480, 270), Media3FlowGeometry.motionSize(1920, 1080, 480))
  }

  @Test
  fun fourHundredPixelProcessingUsesOverlappingEightByEightPatches() {
    val processingSize = Media3FlowGeometry.motionSize(1920, 1080, 400)
    assertEquals(Media3FlowMotionSize(400, 225), processingSize)
    assertEquals(Media3FlowGridSize(99, 55), Media3FlowGeometry.motionGridSize(processingSize.width, processingSize.height))
    assertEquals(4, MEDIA3_FLOW_GRID_STEP)
    assertEquals(8, MEDIA3_FLOW_BLOCK_SIZE)
  }

  @Test
  fun portraitSourceFitsTheSameMotionCapWithoutChangingAspectRatio() {
    assertEquals(Media3FlowMotionSize(270, 480), Media3FlowGeometry.motionSize(1080, 1920, 480))
  }

  @Test
  fun motionGridUsesOnlyValidRegularBlockOriginsAtDivisibleAndNonDivisibleSizes() {
    val nonDivisibleGrid = Media3FlowGeometry.motionGridSize(640, 360)
    assertEquals(Media3FlowGridSize(159, 89), nonDivisibleGrid)
    assertEquals(632, (nonDivisibleGrid.width - 1) * MEDIA3_FLOW_GRID_STEP)
    assertEquals(352, (nonDivisibleGrid.height - 1) * MEDIA3_FLOW_GRID_STEP)
    assertTrue((nonDivisibleGrid.width - 1) * MEDIA3_FLOW_GRID_STEP <= 640 - MEDIA3_FLOW_BLOCK_SIZE)
    assertTrue((nonDivisibleGrid.height - 1) * MEDIA3_FLOW_GRID_STEP <= 360 - MEDIA3_FLOW_BLOCK_SIZE)

    assertEquals(Media3FlowGridSize(157, 89), Media3FlowGeometry.motionGridSize(632, 362))
  }

  @Test
  fun synthesisSamplesMotionAtPatchCentersAndClampsGridEdges() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertEquals(4f, Media3FlowGeometry.motionGridAnchorOffset(), 0f)
    assertTrue(shader.contains("uniform float uGridAnchorOffset;"))
    assertTrue(shader.contains("vec2 gridPos = (p - vec2(uGridAnchorOffset)) / float(uStep);"))
    assertTrue(shader.contains("gridPos = clamp(gridPos, vec2(0.0), vec2(uGrid - ivec2(1)));"))
  }

  @Test
  fun synthesisKeepsMotionFieldContinuousAcrossGridCells() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(shader.contains("vec4 top = mix(flow00, flow10, t.x);"))
    assertTrue(shader.contains("vec4 bottom = mix(flow01, flow11, t.x);"))
    assertTrue(shader.contains("vec4 interpolated = mix(top, bottom, t.y);"))
    assertTrue(shader.contains("return interpolated;"))
    assertFalse(shader.contains("nearestCell"))
    assertFalse(shader.contains("FLOW_EDGE_START_SQUARED"))
    assertFalse(shader.contains("MAX_MATCH_ERROR * edgePenalty"))
    assertTrue(shader.contains("forwardAtSource.z = max(forwardAtSource.z, forwardAtMid.z);"))
    assertTrue(shader.contains("backwardAtTarget.z = max(backwardAtTarget.z, backwardAtMid.z);"))
    assertTrue(shader.contains("float matchQuality = 1.0 - smoothstep(MATCH_ERROR_START, MATCH_ERROR_END, flow.z);"))
  }

  @Test
  fun measuredMatchErrorStillRejectsPoorVectorsAfterRemovingEdgeSnapping() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertFalse(shader.contains("FLOW_EDGE_START_SQUARED"))
    assertFalse(shader.contains("nearestFlow"))
    assertFalse(shader.contains("MAX_MATCH_ERROR * edgePenalty"))

    val goodMeasuredMatchQuality = 1f - smoothstep(0.04f, 0.35f, 0.01f)
    val poorMeasuredMatchQuality = 1f - smoothstep(0.04f, 0.35f, 0.34f)
    assertTrue("A reliable selected edge vector must remain eligible for warping", goodMeasuredMatchQuality >= 0.15f)
    assertTrue("The existing match-error gate must still reject poor vectors", poorMeasuredMatchQuality < 0.15f)
  }

  @Test
  fun motionConfidenceKeepsBaselineBehaviorOnLowTextureRegions() {
    val motionShader = Media3FlowVideoSink.FLOW_COMPUTE_SHADER
    val synthesisShader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(motionShader.contains("imageStore(uFlow, cell, vec4(refinedOffset, best, 1.0));"))
    assertTrue(motionShader.contains("int extendedRadius = min(uCoarseStep * 2, MAX_EXTENDED_SEARCH_RADIUS);"))
    assertTrue(motionShader.contains("for (int dy = -extendedRadius; dy <= extendedRadius; dy += uCoarseStep)"))
    assertTrue(motionShader.contains("int refinementRadius = max(uCoarseStep / 2, 1);"))
    assertTrue(motionShader.contains("float refinementCost = blockSad(origin, offset, 2);"))
    assertTrue(motionShader.contains("refinedOffset.x += refineSubpixelAxis"))
    assertFalse(motionShader.contains("textureEnergy"))
    assertTrue(synthesisShader.contains("return consistency * matchQuality * valid;"))
    assertFalse(synthesisShader.contains("textureConfidence"))
    assertTrue(synthesisShader.contains("vec4 interpolated = mix(top, bottom, t.y);"))
    assertFalse(synthesisShader.contains("nearestCell"))
  }

  @Test
  fun synthesisUsesConfidenceAsAGateAndPreservesSourceTimeBlendWeights() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(shader.contains("float confidence = max(confidence0, confidence1);"))
    assertTrue(shader.contains("if (confidence < VISIBILITY_CONFIDENCE_START) {"))
    assertTrue(shader.contains("float weight0 = (1.0 - uAlpha) * visibility0;"))
    assertTrue(shader.contains("float weight1 = uAlpha * visibility1;"))
    assertTrue(shader.contains("mix(c0, c1, weight1 / weightSum)"))
    assertFalse(shader.contains("colorMismatch"))
    assertTrue(shader.contains("float cycleError0 = length(forwardAtSource.xy + backwardAtForwardEndpoint.xy);"))
    assertTrue(shader.contains("float cycleError1 = length(backwardAtTarget.xy + forwardAtBackwardEndpoint.xy);"))
  }

  @Test
  fun sampledCoverageClassifiesWarpFallbackAndStaticBlendWithoutEarlyReturns() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    val main = shader.substringAfter("void main()")

    assertTrue(shader.contains("layout(std430, binding = 3) writeonly buffer FlowCoverageBuffer"))
    assertTrue(shader.contains("uniform int uCoverageEnabled;"))
    assertTrue(shader.contains("shared uint coverageClass[64];"))
    assertTrue(shader.contains("shared uint interframeChangedClass[64];"))
    assertTrue(shader.contains("pixelClass = 1u;"))
    assertTrue(shader.contains("pixelClass = 2u;"))
    assertTrue(shader.contains("pixelClass = 3u;"))
    assertTrue(shader.contains("barrier();"))
    assertTrue(shader.contains("coverage[groupIndex * 2u] = counts;"))
    assertTrue(shader.contains("coverage[groupIndex * 2u + 1u] = changedCounts;"))
    assertTrue(shader.contains("const float INTERFRAME_CHANGE_DIAGNOSTIC_LUMA_THRESHOLD = 0.01;"))
    assertTrue(shader.contains("const float STATIC_VECTOR_PROBE_THRESHOLD = 0.5;"))
    assertTrue(shader.contains("uCoverageEnabled != 0 && gl_LocalInvocationIndex == 0u"))
    assertTrue(shader.contains("staticVectorClass = 1u;"))
    assertTrue(shader.contains("staticVectorClass = 2u;"))
    assertTrue(shader.contains("staticVectorClass = staticConfidence >= 0.15 ? 3u : 4u;"))
    assertTrue(shader.contains("counts.w = staticVectorClass;"))
    assertTrue(shader.contains("imageStore(uOutput, p, mix(c0, c1, weight1 / weightSum));"))
    assertTrue(shader.contains("imageStore(uOutput, p, uAlpha < 0.5 ? source0 : source1);"))
    assertTrue(shader.contains("imageStore(uOutput, p, mix(source0, source1, uAlpha));"))
    assertFalse(main.contains("return;"))
  }

  @Test
  fun synthesisUsesBidirectionalTargetTimeFlowForBothEndpointWarps() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(shader.contains("return -oneMinusT * t * forward + t * t * backward;"))
    assertTrue(shader.contains("return oneMinusT * oneMinusT * forward - t * oneMinusT * backward;"))
    assertTrue(shader.contains("source0Point = motionPoint + flowToFrame0(uAlpha, forwardAtMid.xy, backwardAtMid.xy);"))
    assertTrue(shader.contains("source1Point = motionPoint + flowToFrame1(uAlpha, forwardAtMid.xy, backwardAtMid.xy);"))
    assertTrue(shader.contains("source0Point = motionPoint + flowToFrame0(uAlpha, forwardAtSource.xy, backwardAtTarget.xy);"))
    assertTrue(shader.contains("source1Point = motionPoint + flowToFrame1(uAlpha, forwardAtSource.xy, backwardAtTarget.xy);"))
  }

  @Test
  fun targetTimeBidirectionalOffsetsReduceToLinearTranslation() {
    val offsets = Media3FlowGeometry.targetTimeEndpointOffsets(
      alpha = 0.25f,
      forward = Media3FlowVector(8f, -4f),
      backward = Media3FlowVector(-8f, 4f),
    )

    assertEquals(-2f, offsets.frame0.x, 0.0001f)
    assertEquals(1f, offsets.frame0.y, 0.0001f)
    assertEquals(6f, offsets.frame1.x, 0.0001f)
    assertEquals(-3f, offsets.frame1.y, 0.0001f)
  }

  @Test
  fun targetTimeBidirectionalOffsetsUseTheOppositeFlowWhenDirectionsDiffer() {
    val offsets = Media3FlowGeometry.targetTimeEndpointOffsets(
      alpha = 0.5f,
      forward = Media3FlowVector(10f, 0f),
      backward = Media3FlowVector(-6f, 0f),
    )

    assertEquals(-4f, offsets.frame0.x, 0.0001f)
    assertEquals(4f, offsets.frame1.x, 0.0001f)
  }

  @Test
  fun targetTimeBidirectionalOffsetsReachBothSourceFramesAtTheEndpoints() {
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
  }

  private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  @Test
  fun sourcesBelowCapKeepTheirNativeMotionDimensions() {
    assertEquals(Media3FlowMotionSize(320, 180), Media3FlowGeometry.motionSize(320, 180, 480))
  }

  @Test
  fun fitCentersTheWholeSourceInsideTheOutputViewport() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 200,
        viewportY = 0,
        viewportWidth = 1200,
        viewportHeight = 900,
        textureScaleX = 1f,
        textureScaleY = 1f,
        textureOffsetX = 0f,
        textureOffsetY = 0f,
      ),
      Media3FlowGeometry.blitGeometry(800, 600, 1600, 900, VideoAspect.Fit),
    )
  }

  @Test
  fun fitLetterboxesAWideSourceWithCenteredTopAndBottomBars() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 0,
        viewportY = 200,
        viewportWidth = 800,
        viewportHeight = 400,
        textureScaleX = 1f,
        textureScaleY = 1f,
        textureOffsetX = 0f,
        textureOffsetY = 0f,
      ),
      Media3FlowGeometry.blitGeometry(1600, 800, 800, 800, VideoAspect.Fit),
    )
  }

  @Test
  fun cropUsesCenteredTextureCoordinatesToFillTheOutputViewport() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 0,
        viewportY = 0,
        viewportWidth = 1600,
        viewportHeight = 900,
        textureScaleX = 1f,
        textureScaleY = 0.75f,
        textureOffsetX = 0f,
        textureOffsetY = 0.125f,
      ),
      Media3FlowGeometry.blitGeometry(800, 600, 1600, 900, VideoAspect.Crop),
    )
  }

  @Test
  fun cropCentersAHorizontalCutForAWideSource() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 0,
        viewportY = 0,
        viewportWidth = 800,
        viewportHeight = 800,
        textureScaleX = 0.5f,
        textureScaleY = 1f,
        textureOffsetX = 0.25f,
        textureOffsetY = 0f,
      ),
      Media3FlowGeometry.blitGeometry(1600, 800, 800, 800, VideoAspect.Crop),
    )
  }

  @Test
  fun stretchUsesTheEntireTextureAndOutputViewport() {
    assertEquals(
      Media3FlowBlitGeometry(
        viewportX = 0,
        viewportY = 0,
        viewportWidth = 1600,
        viewportHeight = 900,
        textureScaleX = 1f,
        textureScaleY = 1f,
        textureOffsetX = 0f,
        textureOffsetY = 0f,
      ),
      Media3FlowGeometry.blitGeometry(800, 600, 1600, 900, VideoAspect.Stretch),
    )
  }
}
