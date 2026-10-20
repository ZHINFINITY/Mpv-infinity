package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowOutputSurfacePolicyTest {
  @Test
  fun dormantFlowSinkDoesNotClaimPlayerSurface() {
    assertFalse(
      Media3FlowOutputSurfacePolicy.shouldCreateEglWindowSurface(
        sinkInitialized = false,
        outputSurfaceValid = true,
        disposed = false,
      ),
    )
  }

  @Test
  fun initializedFlowSinkMayUseValidPlayerSurface() {
    assertTrue(
      Media3FlowOutputSurfacePolicy.shouldCreateEglWindowSurface(
        sinkInitialized = true,
        outputSurfaceValid = true,
        disposed = false,
      ),
    )
  }

  @Test
  fun invalidOrDisposedSurfaceIsNeverClaimed() {
    assertFalse(
      Media3FlowOutputSurfacePolicy.shouldCreateEglWindowSurface(
        sinkInitialized = true,
        outputSurfaceValid = false,
        disposed = false,
      ),
    )
    assertFalse(
      Media3FlowOutputSurfacePolicy.shouldCreateEglWindowSurface(
        sinkInitialized = true,
        outputSurfaceValid = true,
        disposed = true,
      ),
    )
  }
}
