package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowDebugSettingsTest {
  @Test
  fun nativeFlowMakesDebugControlsVisibleWithoutTheMpvFlowRuntime() {
    assertTrue(
      Media3FlowDebugSettingsPolicy.controlsVisible(
        debugBuild = true,
        mpvFlowRuntimeAvailable = false,
        nativeMedia3FlowAvailable = true,
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
  fun resolverAndSoftBlendPreferencesReachTheirIndependentNativeModes() {
    assertEquals(
      Media3FlowDebugSettings(visibilityResolverEnabled = true),
      Media3FlowDebugSettingsPolicy.effective(
        debugBuild = true,
        requestedVisibilityResolver = true,
        requestedSoftBlend = false,
      ),
    )
    assertEquals(
      Media3FlowDebugSettings(softBlendEnabled = true),
      Media3FlowDebugSettingsPolicy.effective(
        debugBuild = true,
        requestedVisibilityResolver = false,
        requestedSoftBlend = true,
      ),
    )
  }

  @Test
  fun softBlendWinsWhenBothPoliciesWerePreviouslySavedAsEnabled() {
    assertEquals(
      Media3FlowDebugSettings(softBlendEnabled = true),
      Media3FlowDebugSettingsPolicy.effective(
        debugBuild = true,
        requestedVisibilityResolver = true,
        requestedSoftBlend = true,
      ),
    )
  }

  @Test
  fun releaseBuildDisablesBothDebugPoliciesAndHidesNativeOnlyControls() {
    assertEquals(
      Media3FlowDebugSettings(),
      Media3FlowDebugSettingsPolicy.effective(
        debugBuild = false,
        requestedVisibilityResolver = true,
        requestedSoftBlend = true,
      ),
    )
    assertFalse(
      Media3FlowDebugSettingsPolicy.controlsVisible(
        debugBuild = false,
        mpvFlowRuntimeAvailable = false,
        nativeMedia3FlowAvailable = true,
      ),
    )
  }
}
