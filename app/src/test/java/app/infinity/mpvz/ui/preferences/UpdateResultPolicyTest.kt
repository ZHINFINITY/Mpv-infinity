package app.infinity.mpvz.ui.preferences

import app.infinity.mpvz.ui.update.UpdateViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateResultPolicyTest {
  @Test fun mapsManualOutcomesToStableUiStates() {
    assertEquals(AboutUpdateResult.Checking, UpdateViewModel.UpdateState.Loading.toAboutUpdateResult())
    assertEquals(AboutUpdateResult.UpToDate, UpdateViewModel.UpdateState.NoUpdate.toAboutUpdateResult())
    assertEquals(AboutUpdateResult.Error, UpdateViewModel.UpdateState.Error.toAboutUpdateResult())
  }
}
