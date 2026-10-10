package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowSynthesisPolicyTest {
  private val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER

  @Test
  fun motionEstimationBuildsABoxReducedPyramidAndUsesSeparatePriorFields() {
    val lumaShader = Media3FlowVideoSink.LUMA_COMPUTE_SHADER
    val motionShader = Media3FlowVideoSink.FLOW_COMPUTE_SHADER

    assertTrue(lumaShader.contains("layout(rgba8, binding = 1) writeonly uniform highp image2D uCoarseLuma;"))
    assertTrue(lumaShader.contains("ivec2 p = coarsePoint * 2 + ivec2(x, y);"))
    assertTrue(lumaShader.contains("sum / max(count, 1.0)"))
    assertTrue(motionShader.contains("layout(rgba16f, binding = 3) readonly uniform highp image2D uPriorFlow;"))
    assertTrue(motionShader.contains("imageLoad(uPriorFlow, priorCell).xy * uPriorScale"))
    assertTrue(motionShader.contains("if (seed == 1) return ivec2(-1, 0);"))
    assertTrue(motionShader.contains("if (seed == 4) return ivec2(0, 1);"))
    assertTrue(motionShader.contains("if (uUsePrior != 0 && seed < 5)"))
    assertEquals(4, MEDIA3_FLOW_COARSE_SEARCH_RADIUS)
    assertEquals(2, MEDIA3_FLOW_FINE_SEARCH_RADIUS)
  }

  @Test
  fun reliabilityUsesTheKnownGoodBaselineErrorRanges() {
    assertTrue(shader.contains("const float CYCLE_ERROR_START = 1.0;"))
    assertTrue(shader.contains("const float CYCLE_ERROR_END = 6.0;"))
    assertTrue(shader.contains("const float MATCH_ERROR_START = 0.04;"))
    assertTrue(shader.contains("const float MATCH_ERROR_END = 0.35;"))
    assertTrue(shader.contains("smoothstep(CYCLE_ERROR_START, CYCLE_ERROR_END, cycleError)"))
    assertTrue(shader.contains("smoothstep(MATCH_ERROR_START, MATCH_ERROR_END, flow.z)"))
  }

  @Test
  fun oneReliableSourceCanCoverAnOcclusionButTwoUnreliableSourcesStillFallback() {
    assertTrue(shader.contains("float confidence = max(confidence0, confidence1);"))
    assertTrue(shader.contains("if (confidence < VISIBILITY_CONFIDENCE_START)"))
    assertTrue(shader.contains("float visibility0 = smoothstep(VISIBILITY_CONFIDENCE_START, VISIBILITY_CONFIDENCE_END, confidence0);"))
    assertTrue(shader.contains("float visibility1 = smoothstep(VISIBILITY_CONFIDENCE_START, VISIBILITY_CONFIDENCE_END, confidence1);"))
    assertTrue(shader.contains("if (weightSum <= 0.0001) {"))
    assertTrue(shader.contains("float cycleError0 = length(forwardAtSource.xy + backwardAtForwardEndpoint.xy);"))
    assertTrue(shader.contains("float cycleError1 = length(backwardAtTarget.xy + forwardAtBackwardEndpoint.xy);"))
    assertTrue(shader.contains("float valid0 = inBounds(uv0Raw);"))
    assertTrue(shader.contains("float valid1 = inBounds(uv1Raw);"))
    assertTrue(shader.contains("valid0 *= inBounds(cyclePoint0 / vec2(uMotionSize));"))
    assertTrue(shader.contains("valid1 *= inBounds(cyclePoint1 / vec2(uMotionSize));"))
    assertFalse(shader.contains("nearestCell"))
  }

  @Test
  fun vectorFieldUsesPerSourceLumaGuidanceWithoutHardCellSnapping() {
    assertTrue(shader.contains("uniform sampler2D uLuma0;"))
    assertTrue(shader.contains("uniform sampler2D uLuma1;"))
    assertTrue(shader.contains("float flowGuideAt(vec2 uv, int direction)"))
    assertTrue(shader.contains("vec4 flowAtEdgeAware(vec2 p, int direction)"))
    assertTrue(shader.contains("float variance = spatial00 * delta00 * delta00"))
    assertTrue(shader.contains("exp(-(delta00 * delta00) / safeVariance)"))
    assertTrue(shader.contains("flowAtEdgeAware(motionPoint, 0)"))
    assertTrue(shader.contains("flowAtEdgeAware(motionPoint, 1)"))
    assertTrue(shader.contains("flowAtEdgeAware(source0Point, 0)"))
    assertTrue(shader.contains("flowAtEdgeAware(source1Point, 1)"))
    assertTrue(shader.contains("if (totalWeight <= 0.000001) return flowAt(p, direction);"))
    assertFalse(shader.contains("nearestCell"))
  }
}
