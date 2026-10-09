package app.infinity.mpvz.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowSynthesisPolicyTest {
  private val shader = Media3FlowVideoSink.SYNTH_COMPUTE_SHADER

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
  fun fallbackStillRequiresBothDirectionsAndKeepsBoundsAndMotionEdgeGuards() {
    assertTrue(shader.contains("float confidence = min(confidence0, confidence1);"))
    assertTrue(shader.contains("if (confidence < 0.15)"))
    assertTrue(shader.contains("float valid0 = inBounds(uv0Raw);"))
    assertTrue(shader.contains("float valid1 = inBounds(uv1Raw);"))
    assertTrue(shader.contains("if (localMotionDisagreementSq > FLOW_EDGE_START_SQUARED)"))
  }
}
