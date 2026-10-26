package app.infinity.mpvz.ui.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckRequestPolicyTest {
  @Test fun activeManualCheckCannotBeSupersededByAnAutomaticRequest() {
    assertFalse(
      UpdateCheckRequestPolicy.shouldStartAutomaticCheck(
        manualCheckInProgress = true,
        manualRequestActive = true,
      ),
    )
    assertTrue(
      UpdateCheckRequestPolicy.shouldStartAutomaticCheck(
        manualCheckInProgress = true,
        manualRequestActive = false,
      ),
    )
    assertTrue(
      UpdateCheckRequestPolicy.shouldStartAutomaticCheck(
        manualCheckInProgress = false,
        manualRequestActive = true,
      ),
    )
  }
}
