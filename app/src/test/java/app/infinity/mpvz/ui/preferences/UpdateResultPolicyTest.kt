package app.infinity.mpvz.ui.preferences

import app.infinity.mpvz.ui.update.UpdateViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateResultPolicyTest {
  @Test fun mapsManualOutcomesToStableUiStates() {
    assertEquals(AboutUpdateResult.Checking, UpdateViewModel.UpdateState.Loading.toAboutUpdateResult())
    assertEquals(AboutUpdateResult.UpToDate, UpdateViewModel.UpdateState.NoUpdate.toAboutUpdateResult())
    assertEquals(AboutUpdateResult.Error, UpdateViewModel.UpdateState.Error.toAboutUpdateResult())
  }

  @Test fun aboutSheetIsVisibleOnlyForManualStatusChecks() {
    assertFalse(UpdateViewModel.UpdateState.Loading.shouldShowAboutSheet(isManualCheck = false))
    assertFalse(UpdateViewModel.UpdateState.NoUpdate.shouldShowAboutSheet(isManualCheck = false))
    assertFalse(UpdateViewModel.UpdateState.Error.shouldShowAboutSheet(isManualCheck = false))
    assertTrue(UpdateViewModel.UpdateState.Loading.shouldShowAboutSheet(isManualCheck = true))
    assertTrue(UpdateViewModel.UpdateState.NoUpdate.shouldShowAboutSheet(isManualCheck = true))
    assertTrue(UpdateViewModel.UpdateState.Error.shouldShowAboutSheet(isManualCheck = true))
    assertFalse(UpdateViewModel.UpdateState.Idle.shouldShowAboutSheet(isManualCheck = true))
  }
}
