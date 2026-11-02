package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowDebugSettingsTest {
  @Test
  fun releaseBuildShowsControlsWhenEitherFlowRuntimeIsAvailable() {
    assertTrue(
      Media3FlowDebugSettingsPolicy.controlsVisible(
        debugBuild = false,
        mpvFlowRuntimeAvailable = true,
        nativeMedia3FlowAvailable = false,
      ),
    )
    assertTrue(
      Media3FlowDebugSettingsPolicy.controlsVisible(
        debugBuild = false,
        mpvFlowRuntimeAvailable = false,
        nativeMedia3FlowAvailable = true,
      ),
    )
  }

  @Test
  fun releaseBuildHidesControlsWhenNeitherFlowRuntimeIsAvailable() {
    assertFalse(
      Media3FlowDebugSettingsPolicy.controlsVisible(
        debugBuild = false,
        mpvFlowRuntimeAvailable = false,
        nativeMedia3FlowAvailable = false,
      ),
    )
  }

  @Test
  fun nativeFlowEnablesAnOffControlAndPreservesAnOnControlWhenUnavailable() {
    assertTrue(
      Media3FlowDebugSettingsPolicy.controlEnabled(
        mpvFlowAvailable = false,
        nativeMedia3FlowAvailable = true,
        currentValue = false,
      ),
    )
    assertTrue(
      Media3FlowDebugSettingsPolicy.controlEnabled(
        mpvFlowAvailable = false,
        nativeMedia3FlowAvailable = false,
        currentValue = true,
      ),
    )
    assertFalse(
      Media3FlowDebugSettingsPolicy.controlEnabled(
        mpvFlowAvailable = false,
        nativeMedia3FlowAvailable = false,
        currentValue = false,
      ),
    )
  }

  @Test
  fun releaseBuildAppliesEitherNativeModeAndSoftBlendWinsOnConflict() {
    assertEquals(
      Media3FlowDebugSettings(visibilityResolverEnabled = true),
      Media3FlowDebugSettingsPolicy.effective(
        debugBuild = false,
        requestedVisibilityResolver = true,
        requestedSoftBlend = false,
      ),
    )
    assertEquals(
      Media3FlowDebugSettings(softBlendEnabled = true),
      Media3FlowDebugSettingsPolicy.effective(
        debugBuild = false,
        requestedVisibilityResolver = false,
        requestedSoftBlend = true,
      ),
    )
    assertEquals(
      Media3FlowDebugSettings(softBlendEnabled = true),
      Media3FlowDebugSettingsPolicy.effective(
        debugBuild = false,
        requestedVisibilityResolver = true,
        requestedSoftBlend = true,
      ),
    )
  }

  @Test
  fun releaseBuildHonorsMpvFlowRequestButKeepsRuntimeAndConfigOwnershipChecks() {
    assertTrue(
      Media3FlowDebugSettingsPolicy.mpvFlowFeatureEnabled(
        debugBuild = false,
        requested = true,
        interpolationActive = true,
        vulkanBackend = true,
        mpvConfOwnsOption = false,
      ),
    )
    assertFalse(
      Media3FlowDebugSettingsPolicy.mpvFlowFeatureEnabled(
        debugBuild = false,
        requested = true,
        interpolationActive = false,
        vulkanBackend = true,
        mpvConfOwnsOption = false,
      ),
    )
    assertFalse(
      Media3FlowDebugSettingsPolicy.mpvFlowFeatureEnabled(
        debugBuild = false,
        requested = true,
        interpolationActive = true,
        vulkanBackend = false,
        mpvConfOwnsOption = false,
      ),
    )
    assertFalse(
      Media3FlowDebugSettingsPolicy.mpvFlowFeatureEnabled(
        debugBuild = false,
        requested = true,
        interpolationActive = true,
        vulkanBackend = true,
        mpvConfOwnsOption = true,
      ),
    )
  }
}
