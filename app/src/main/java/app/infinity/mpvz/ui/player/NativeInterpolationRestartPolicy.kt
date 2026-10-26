/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.player

/** Exact Native playback identity and intent captured before a controlled interpolation reload. */
internal data class NativeInterpolationRestartState(
  val stableMediaId: String,
  val mediaUri: String,
  val positionMs: Long,
  val playWhenReady: Boolean,
  val mediaRequestGeneration: Long,
)

internal enum class NativeInterpolationRestartResult {
  APPLIED,
  FLOW_UNAVAILABLE,
  SUPERSEDED,
  PLAYBACK_FAILED,
}

internal object NativeInterpolationRestartPolicy {
  fun capture(
    stableMediaId: String?,
    mediaUri: String?,
    positionMs: Long?,
    playWhenReady: Boolean,
    mediaRequestGeneration: Long,
  ): NativeInterpolationRestartState? {
    val uri = mediaUri?.takeIf { it.isNotBlank() } ?: return null
    val mediaId = stableMediaId?.takeIf { it.isNotBlank() } ?: uri
    val position = positionMs?.takeIf { it >= 0L } ?: return null
    return NativeInterpolationRestartState(
      stableMediaId = mediaId,
      mediaUri = uri,
      positionMs = position,
      playWhenReady = playWhenReady,
      mediaRequestGeneration = mediaRequestGeneration,
    )
  }

  fun stillOwns(
    state: NativeInterpolationRestartState,
    currentStableMediaId: String?,
    currentMediaUri: String?,
    currentMediaRequestGeneration: Long,
    nativeActive: Boolean,
    ownsPlaybackSession: Boolean,
  ): Boolean {
    val currentId = currentStableMediaId?.takeIf { it.isNotBlank() } ?: currentMediaUri
    return nativeActive && ownsPlaybackSession &&
      currentId == state.stableMediaId && currentMediaUri == state.mediaUri &&
      currentMediaRequestGeneration == state.mediaRequestGeneration
  }
}
