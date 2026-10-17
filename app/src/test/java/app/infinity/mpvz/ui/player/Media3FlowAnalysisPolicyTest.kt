package app.infinity.mpvz.ui.player

import app.infinity.mpvz.preferences.effectiveMpvFlowMaxDimension
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3FlowAnalysisPolicyTest {
  @Test
  fun media3UsesDenserBoundedAnalysisSamplingAtHighOutputRates() {
    assertEquals(528, Media3FlowAnalysisPolicy.maxDimension(targetFps = 120, quality = 60))
    assertEquals(544, Media3FlowAnalysisPolicy.maxDimension(targetFps = 120, quality = 100))
    assertEquals(640, Media3FlowAnalysisPolicy.maxDimension(targetFps = 60, quality = 100))
  }

  @Test
  fun media3BoundedAllowanceUsesTheUpdatedSharedQualityProfile() {
    assertEquals(432, effectiveMpvFlowMaxDimension(targetFps = 120, quality = 60))
  }
}
