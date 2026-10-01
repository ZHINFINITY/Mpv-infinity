package app.infinity.mpvz.utils.history

import kotlinx.coroutines.CancellationException

internal suspend fun clearWatchHistory(
  backfillWatchStatistics: suspend () -> Unit,
  clearMediaHistory: suspend () -> Unit,
  clearStreamHistory: suspend () -> Unit,
) {
  // Playback resume stores are deliberately not part of this operation.
  // If the snapshot cannot be persisted, retain source rows rather than losing statistics.
  backfillWatchStatistics()
  var failure: Exception? = null
  try {
    clearMediaHistory()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Exception) {
    failure = error
  }
  try {
    clearStreamHistory()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Exception) {
    if (failure == null) failure = error else failure.addSuppressed(error)
  }
  failure?.let { throw it }
}
