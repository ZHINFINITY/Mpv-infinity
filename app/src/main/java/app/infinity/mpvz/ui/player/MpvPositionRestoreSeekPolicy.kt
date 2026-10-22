package app.infinity.mpvz.ui.player

import kotlin.math.abs

/**
 * Same-position restores after an MPV core replacement (frame-interpolation reload, engine
 * handoff) must not depend on the user's hr-seek preference: a keyframe seek can land several
 * seconds before the saved position on long-GOP video, which both moves playback and makes the
 * restart confirmation time out while the video is held paused.
 */
object MpvPositionRestoreSeekPolicy {
  /** One video frame at 23.976 fps is ~42 ms; allow for container timestamp rounding. */
  const val CONFIRM_TOLERANCE_SECONDS = 0.5

  fun command(positionSeconds: Double): Array<String> =
    arrayOf("seek", positionSeconds.coerceAtLeast(0.0).toString(), "absolute+exact")

  fun isRestored(actualSeconds: Double?, targetSeconds: Double, seeking: Boolean?): Boolean =
    actualSeconds != null && actualSeconds.isFinite() && seeking != true &&
      abs(actualSeconds - targetSeconds.coerceAtLeast(0.0)) <= CONFIRM_TOLERANCE_SECONDS
}
