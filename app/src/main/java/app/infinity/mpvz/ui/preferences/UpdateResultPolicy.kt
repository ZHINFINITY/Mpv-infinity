package app.infinity.mpvz.ui.preferences

import app.infinity.mpvz.ui.update.UpdateViewModel

/** Pure mapping used by the About update sheet; keeps presentation states deterministic. */
sealed interface AboutUpdateResult {
  data object Checking : AboutUpdateResult
  data object UpToDate : AboutUpdateResult
  data object Available : AboutUpdateResult
  data object Error : AboutUpdateResult
}

fun UpdateViewModel.UpdateState.toAboutUpdateResult(): AboutUpdateResult = when (this) {
  UpdateViewModel.UpdateState.Loading -> AboutUpdateResult.Checking
  UpdateViewModel.UpdateState.NoUpdate -> AboutUpdateResult.UpToDate
  is UpdateViewModel.UpdateState.Available, is UpdateViewModel.UpdateState.ReadyToInstall -> AboutUpdateResult.Available
  UpdateViewModel.UpdateState.Error -> AboutUpdateResult.Error
  UpdateViewModel.UpdateState.Idle -> AboutUpdateResult.Checking
}
