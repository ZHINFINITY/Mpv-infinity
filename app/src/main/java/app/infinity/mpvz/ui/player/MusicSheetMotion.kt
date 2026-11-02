/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * Where the browser music sheet is in its life cycle.
 *
 * The sheet only ever rests in [Collapsed] or [Expanded]. [Dragging] and [Settling] are transient:
 * every way out of a drag (finger up, gesture cancelled, pointer input restarted, composition
 * leaving) produces a settle target, and every settle ends exactly on 0 or 1.
 */
enum class MusicSheetPhase { Collapsed, Dragging, Settling, Expanded }

/**
 * Framework-free state machine behind the browser music sheet.
 *
 * Progress is `0` for the mini player and `1` for the full player. The Compose layer feeds it
 * pointer and animation events and only ever animates toward [target]; all the decisions that
 * caused the old sheet to freeze half-way live here so they can be unit tested.
 */
class MusicSheetMotion(
  /** Velocity, in px/s, above which a release follows the fling instead of the position. */
  private val flingVelocityPx: Float = DEFAULT_FLING_VELOCITY_PX,
) {
  var phase: MusicSheetPhase = MusicSheetPhase.Collapsed
    private set

  var progress: Float = 0f
    private set

  /** Where the running or pending settle is headed; `null` only while resting or dragging. */
  var target: Float? = null
    private set

  /** Bumped every time a new settle is requested, so a stale animation can tell it lost. */
  var settleGeneration: Int = 0
    private set

  val isResting: Boolean
    get() = phase == MusicSheetPhase.Collapsed || phase == MusicSheetPhase.Expanded

  /** Whether the sheet is on screen at all (anything but a resting collapsed sheet). */
  val isShowing: Boolean
    get() = phase != MusicSheetPhase.Collapsed

  /** The finger took the sheet. A running settle is interrupted where it currently is. */
  fun beginDrag() {
    phase = MusicSheetPhase.Dragging
    target = null
  }

  /**
   * Moves the sheet with the finger. [deltaYPx] is positive downward, [travelPx] the distance
   * between the collapsed and expanded positions. Ignored unless a drag is active.
   */
  fun dragBy(deltaYPx: Float, travelPx: Float) {
    if (phase != MusicSheetPhase.Dragging) return
    if (!travelPx.isFinite() || travelPx <= 0f || !deltaYPx.isFinite()) return
    progress = (progress - deltaYPx / travelPx).coerceIn(0f, 1f)
  }

  /** Finger lifted. Returns the settle target, which is always 0 or 1. */
  fun release(velocityYPx: Float): Float {
    val destination =
      if (phase == MusicSheetPhase.Dragging) releaseTarget(progress, velocityYPx, flingVelocityPx)
      else nearest(progress)
    return startSettle(destination)
  }

  /** The gesture was cancelled (another handler claimed it, input restarted). Settle by position. */
  fun cancelDrag(): Float? {
    if (phase != MusicSheetPhase.Dragging) return null
    return startSettle(nearest(progress))
  }

  /** Tap on the mini player, or a programmatic open. Retargets from wherever the sheet is. */
  fun expand(): Float = startSettle(1f)

  /** Back press, header chevron, or the session going away. Retargets from wherever it is. */
  fun collapse(): Float = startSettle(0f)

  /** One animation frame. Ignored while the finger owns the sheet. */
  fun onAnimationFrame(value: Float) {
    if (phase != MusicSheetPhase.Settling || !value.isFinite()) return
    progress = value.coerceIn(0f, 1f)
  }

  /**
   * The animation for [generation] stopped, either finished or cancelled. When it was still the
   * current settle, the sheet lands exactly on its target even if the animation was cut short, so
   * nothing can leave it frozen part-way. Returns true when this call ended the settle.
   */
  fun onSettleFinished(generation: Int): Boolean {
    if (phase != MusicSheetPhase.Settling || generation != settleGeneration) return false
    val destination = target ?: nearest(progress)
    progress = destination
    target = null
    phase = if (destination >= 1f) MusicSheetPhase.Expanded else MusicSheetPhase.Collapsed
    return true
  }

  /** Jump straight to a resting state, without animation (session ended, activity recreated). */
  fun snapTo(expanded: Boolean) {
    settleGeneration++
    progress = if (expanded) 1f else 0f
    target = null
    phase = if (expanded) MusicSheetPhase.Expanded else MusicSheetPhase.Collapsed
  }

  private fun startSettle(destination: Float): Float {
    settleGeneration++
    target = destination
    phase =
      if (progress == destination) {
        // Already there: no animation needed, rest immediately.
        target = null
        if (destination >= 1f) MusicSheetPhase.Expanded else MusicSheetPhase.Collapsed
      } else {
        MusicSheetPhase.Settling
      }
    return destination
  }

  companion object {
    const val DEFAULT_FLING_VELOCITY_PX = 1_800f

    fun nearest(progress: Float): Float = if (progress.isFinite() && progress >= 0.5f) 1f else 0f

    /** Fast flings win; otherwise the sheet goes to whichever end it is closer to. */
    fun releaseTarget(progress: Float, velocityYPx: Float, flingVelocityPx: Float): Float =
      when {
        !velocityYPx.isFinite() -> nearest(progress)
        velocityYPx <= -flingVelocityPx -> 1f
        velocityYPx >= flingVelocityPx -> 0f
        else -> nearest(progress)
      }

    /** Converts a release velocity in px/s into the progress-per-second the spring starts with. */
    fun progressVelocity(velocityYPx: Float, travelPx: Float): Float =
      if (!velocityYPx.isFinite() || !travelPx.isFinite() || travelPx <= 0f) 0f
      else (-velocityYPx / travelPx).coerceIn(-12f, 12f)
  }
}
