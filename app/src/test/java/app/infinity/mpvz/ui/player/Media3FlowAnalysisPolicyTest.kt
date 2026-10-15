package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.effectiveMpvFlowMaxDimension
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3FlowAnalysisPolicyTest {
  @Test
  fun media3GetsTwoAdditionalAlignedAnalysisTiles() {
    assertEquals(448, Media3FlowAnalysisPolicy.maxDimension(targetFps = 120, quality = 60))
    assertEquals(480, Media3FlowAnalysisPolicy.maxDimension(targetFps = 120, quality = 100))
    assertEquals(640, Media3FlowAnalysisPolicy.maxDimension(targetFps = 60, quality = 100))
  }

  @Test
  fun sharedMpvProfileAndTargetRatePolicyRemainUnchanged() {
    assertEquals(416, effectiveMpvFlowMaxDimension(targetFps = 120, quality = 60))
  }
}
