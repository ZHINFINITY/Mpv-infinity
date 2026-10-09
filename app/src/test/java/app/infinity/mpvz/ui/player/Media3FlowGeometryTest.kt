package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowGeometryTest {
  @Test
  fun motionSearchKeepsTheKnownGoodBoundedAnalysisRadius() {
    assertEquals(4, MEDIA3_FLOW_SEARCH_RADIUS)
  }

  @Test
  fun fullHdSourceUsesReducedMotionGrid() {
    assertEquals(Media3FlowMotionSize(480, 270), Media3FlowGeometry.motionSize(1920, 1080, 480))
  }

  @Test
  fun portraitSourceFitsTheSameMotionCapWithoutChangingAspectRatio() {
    assertEquals(Media3FlowMotionSize(270, 480), Media3FlowGeometry.motionSize(1080, 1920, 480))
  }

  @Test
  fun motionGridUsesOnlyValidRegularBlockOriginsAtDivisibleAndNonDivisibleSizes() {
    val nonDivisibleGrid = Media3FlowGeometry.motionGridSize(640, 360)
    assertEquals(Media3FlowGridSize(106, 59), nonDivisibleGrid)
    assertEquals(630, (nonDivisibleGrid.width - 1) * MEDIA3_FLOW_GRID_STEP)
    assertEquals(348, (nonDivisibleGrid.height - 1) * MEDIA3_FLOW_GRID_STEP)
    assertTrue((nonDivisibleGrid.width - 1) * MEDIA3_FLOW_GRID_STEP <= 640 - MEDIA3_FLOW_BLOCK_SIZE)
    assertTrue((nonDivisibleGrid.height - 1) * MEDIA3_FLOW_GRID_STEP <= 360 - MEDIA3_FLOW_BLOCK_SIZE)

    assertEquals(Media3FlowGridSize(105, 60), Media3FlowGeometry.motionGridSize(632, 362))
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
  fun synthesisSelectsOneVectorAcrossLocalMotionDiscontinuities() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(shader.contains("float localMotionDisagreementSq = max("))
    assertTrue(shader.contains("smoothstep(FLOW_EDGE_START_SQUARED, FLOW_EDGE_END_SQUARED, localMotionDisagreementSq)"))
    assertTrue(shader.contains("if (localMotionDisagreementSq > FLOW_EDGE_START_SQUARED) {"))
    assertTrue(shader.contains("ivec2 nearestCell = ivec2(t.x < 0.5 ? a.x : b.x, t.y < 0.5 ? a.y : b.y);"))
    assertTrue(shader.contains("interpolated.xy = nearestFlow.xy;"))
    assertTrue(shader.contains("interpolated.w = nearestFlow.w;"))
    assertTrue(shader.contains("interpolated.z = max(interpolated.z, MAX_MATCH_ERROR * edgePenalty);"))
    assertTrue(shader.contains("forwardAtSource.z = max(forwardAtSource.z, forwardAtMid.z);"))
    assertTrue(shader.contains("backwardAtTarget.z = max(backwardAtTarget.z, backwardAtMid.z);"))
    assertTrue(shader.contains("float matchQuality = 1.0 - smoothstep(0.06, MAX_MATCH_ERROR, flow.z);"))
  }

  @Test
  fun motionConfidenceKeepsBaselineBehaviorOnLowTextureRegions() {
    val motionShader = Media3FlowVideoSink.FLOW_COMPUTE_SHADER
    val synthesisShader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(motionShader.contains("imageStore(uFlow, cell, vec4(vec2(bestOffset), best, 1.0));"))
    assertFalse(motionShader.contains("textureEnergy"))
    assertTrue(synthesisShader.contains("return consistency * matchQuality * valid;"))
    assertFalse(synthesisShader.contains("textureConfidence"))
    assertTrue(synthesisShader.contains("if (localMotionDisagreementSq > FLOW_EDGE_START_SQUARED) {"))
    assertTrue(synthesisShader.contains("interpolated.xy = nearestFlow.xy;"))
  }

  @Test
  fun synthesisUsesConfidenceAsAGateAndPreservesSourceTimeBlendWeights() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(shader.contains("float confidence = min(confidence0, confidence1);"))
    assertTrue(shader.contains("if (confidence < 0.15) {"))
    assertTrue(shader.contains("imageStore(uOutput, p, mix(c0, c1, uAlpha));"))
    assertFalse(shader.contains("colorMismatch"))
    assertFalse(shader.contains("(1.0 - uAlpha) * confidence0"))
    assertFalse(shader.contains("uAlpha * confidence1"))
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
    assertTrue(shader.contains("imageStore(uOutput, p, mix(c0, c1, uAlpha));"))
    assertTrue(shader.contains("imageStore(uOutput, p, uAlpha < 0.5 ? source0 : source1);"))
    assertTrue(shader.contains("imageStore(uOutput, p, mix(source0, source1, uAlpha));"))
    assertFalse(main.contains("return;"))
  }

  @Test
  fun synthesisSubtractsBackwardFlowWhenSamplingFrameOne() {
    val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER
    assertTrue(shader.contains("source1Point = motionPoint - (1.0 - uAlpha) * backwardAtMid.xy;"))
    assertTrue(shader.contains("source1Point = motionPoint - (1.0 - uAlpha) * backwardAtTarget.xy;"))
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
