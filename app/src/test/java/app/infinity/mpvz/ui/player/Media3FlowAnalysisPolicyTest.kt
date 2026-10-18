package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.effectiveMpvFlowMaxDimension
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3FlowAnalysisPolicyTest {
  @Test
  fun media3UsesDenserBoundedAnalysisSamplingAtHighOutputRates() {
    assertEquals(512, Media3FlowAnalysisPolicy.maxDimension(targetFps = 120, quality = 60))
    assertEquals(544, Media3FlowAnalysisPolicy.maxDimension(targetFps = 120, quality = 100))
    assertEquals(640, Media3FlowAnalysisPolicy.maxDimension(targetFps = 60, quality = 100))
  }

  @Test
  fun sharedMpvProfileAndTargetRatePolicyRemainUnchanged() {
    assertEquals(416, effectiveMpvFlowMaxDimension(targetFps = 120, quality = 60))
    assertEquals(4, Media3FlowAnalysisPolicy.searchRadius(targetFps = 120, quality = 60))
  }

  @Test
  fun targetAwareSearchRadiusMatchesMpvProfileAtNativeRatesAndClampsQuality() {
    assertEquals(6, Media3FlowAnalysisPolicy.searchRadius(90, -10))
    assertEquals(8, Media3FlowAnalysisPolicy.searchRadius(90, 101))
    assertEquals(4, Media3FlowAnalysisPolicy.searchRadius(120, 0))
    assertEquals(5, Media3FlowAnalysisPolicy.searchRadius(120, 100))
    assertEquals(3, Media3FlowAnalysisPolicy.searchRadius(144, 0))
    assertEquals(4, Media3FlowAnalysisPolicy.searchRadius(144, 100))
  }
}
