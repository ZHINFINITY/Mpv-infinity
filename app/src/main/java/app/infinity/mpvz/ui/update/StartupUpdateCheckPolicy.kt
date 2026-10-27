package app.infinity.mpvz.ui.update

/** Prevents a delayed startup check from replacing a newer user-initiated update request. */
internal object StartupUpdateCheckPolicy {
  fun shouldRunDelayedStartupCheck(
    scheduledGeneration: Long,
    currentGeneration: Long,
    manualCheckInProgress: Boolean,
  ): Boolean = scheduledGeneration == currentGeneration && !manualCheckInProgress
}
