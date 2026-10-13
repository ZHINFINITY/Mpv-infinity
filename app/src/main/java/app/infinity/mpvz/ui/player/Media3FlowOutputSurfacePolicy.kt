package app.infinity.mpvz.ui.player

/** Keeps a dormant Flow sink from claiming the player's output BufferQueue. */
internal object Media3FlowOutputSurfacePolicy {
  fun shouldCreateEglWindowSurface(
    sinkInitialized: Boolean,
    outputSurfaceValid: Boolean,
    disposed: Boolean,
  ): Boolean = sinkInitialized && outputSurfaceValid && !disposed
}
