package app.infinity.mpvz.ui.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupUpdateCheckPolicyTest {
  @Test fun startupCheckRunsOnlyWhileItsScheduledRequestIsCurrent() {
    assertTrue(
      StartupUpdateCheckPolicy.shouldRunDelayedStartupCheck(
        scheduledGeneration = 4L,
        currentGeneration = 4L,
        manualCheckInProgress = false,
      ),
    )
    assertFalse(
      StartupUpdateCheckPolicy.shouldRunDelayedStartupCheck(
        scheduledGeneration = 4L,
        currentGeneration = 5L,
        manualCheckInProgress = false,
      ),
    )
    assertFalse(
      StartupUpdateCheckPolicy.shouldRunDelayedStartupCheck(
        scheduledGeneration = 4L,
        currentGeneration = 4L,
        manualCheckInProgress = true,
      ),
    )
  }
}
