/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvAspectPropertyUpdatePolicyTest {
  @Test
  fun alreadyAppliedAspectTargetsDoNotIssueRedundantWrites() {
    assertFalse(MpvAspectPropertyUpdatePolicy.needsUpdate(0.0, 0.0))
    assertFalse(MpvAspectPropertyUpdatePolicy.needsUpdate(1.0, 1.0))
    assertFalse(
      MpvAspectPropertyUpdatePolicy.needsUpdate(
        MpvAspectPropertyUpdatePolicy.NO_ASPECT_OVERRIDE,
        MpvAspectPropertyUpdatePolicy.NO_ASPECT_OVERRIDE,
      ),
    )
    assertFalse(MpvAspectPropertyUpdatePolicy.needsUpdate(2.1656251, 2.165625))
  }

  @Test
  fun missingOrChangedAspectTargetsStillIssueWrites() {
    assertTrue(MpvAspectPropertyUpdatePolicy.needsUpdate(null, 0.0))
    assertTrue(MpvAspectPropertyUpdatePolicy.needsUpdate(0.0, 1.0))
    assertTrue(MpvAspectPropertyUpdatePolicy.needsUpdate(1.0, 2.165625))
  }
}
