/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MpvSurfaceFrameRateHintTest {
  @Test
  fun interpolationOutputCadenceOverridesSourceCadence() {
    assertEquals(60.0, resolveSurfaceFrameRateHint(24.0, 60.0)!!, 0.0)
  }

  @Test
  fun sourceCadenceIsUsedWhenInterpolationIsInactive() {
    assertEquals(23.976, resolveSurfaceFrameRateHint(23.976, null)!!, 0.0)
  }

  @Test
  fun invalidInterpolationCadenceFallsBackToValidSourceCadence() {
    assertEquals(24.0, resolveSurfaceFrameRateHint(24.0, Double.NaN)!!, 0.0)
  }

  @Test
  fun invalidRatesProduceNoSurfaceHint() {
    assertNull(resolveSurfaceFrameRateHint(0.0, -1.0))
  }
}
