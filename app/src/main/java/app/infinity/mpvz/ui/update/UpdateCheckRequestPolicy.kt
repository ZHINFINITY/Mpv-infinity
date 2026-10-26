package app.infinity.mpvz.ui.update

/** Keeps an automatic check from superseding a manual request that is still running. */
internal object UpdateCheckRequestPolicy {
  fun shouldStartAutomaticCheck(
    manualCheckInProgress: Boolean,
    manualRequestActive: Boolean,
  ): Boolean = !(manualCheckInProgress && manualRequestActive)
}
