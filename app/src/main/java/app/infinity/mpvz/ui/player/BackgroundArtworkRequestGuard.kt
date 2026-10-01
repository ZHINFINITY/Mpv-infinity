package app.infinity.mpvz.ui.player

/** Guards asynchronous fallback-artwork lookups against stale and duplicate requests. */
internal class BackgroundArtworkRequestGuard {
  internal data class Request internal constructor(val key: String, internal val generation: Long)

  private var nextGeneration = 0L
  private var inFlight: Request? = null
  private var lastSettledKey: String? = null
  private var lastReadyKey: String? = null

  /** Starts a lookup only after the active media is ready and when this key is not already active or settled. */
  fun begin(key: String, mediaReady: Boolean): Request? {
    if (!mediaReady || key.isBlank() || key == inFlight?.key || key == lastSettledKey) return null

    return Request(key, ++nextGeneration).also { inFlight = it }
  }

  /** A result may publish only while its exact request is still active for the ready current key. */
  fun isCurrent(request: Request, currentKey: String, mediaReady: Boolean): Boolean =
    mediaReady && request == inFlight && request.key == currentKey

  fun hasReadyArtwork(key: String): Boolean = key == lastReadyKey

  /** Older/cancelled requests cannot clear or settle a newer request. */
  fun complete(request: Request, settled: Boolean, hasArtwork: Boolean) {
    if (inFlight != request) return

    inFlight = null
    if (settled) {
      lastSettledKey = request.key
      if (hasArtwork) lastReadyKey = request.key
    }
  }
}
