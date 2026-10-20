/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/** Commands emitted by the hold-to-speed gesture; execution remains engine-owned. */
data class SpeedGestureCommand(val speed: Float)

/**
 * Pure policy for the temporary speed override used by the player gesture.
 *
 * The captured speed is deliberately carried through unchanged (apart from the same
 * safety bounds as PlayerViewModel), so user speed preferences are restored rather than
 * assuming normal speed.  A command is omitted when it would be a no-op; this is the
 * important distinction from repeatedly writing a ramp of intermediate MPV speeds.
 */
object SpeedGesturePolicy {
  const val HOLD_SPEED = 2f
  private const val EPSILON = 0.01f
  private const val MIN_SPEED = 0.25f
  private const val MAX_SPEED = 8f

  fun enter(originalSpeed: Float, targetSpeed: Float = HOLD_SPEED): List<SpeedGestureCommand> {
    val original = safeSpeed(originalSpeed)
    val target = safeSpeed(targetSpeed)
    return if (kotlin.math.abs(original - target) <= EPSILON) emptyList() else listOf(SpeedGestureCommand(target))
  }

  fun restore(originalSpeed: Float, locked: Boolean): List<SpeedGestureCommand> {
    if (locked) return emptyList()
    return listOf(SpeedGestureCommand(safeSpeed(originalSpeed)))
  }

  private fun safeSpeed(speed: Float): Float =
    if (speed.isFinite()) speed.coerceIn(MIN_SPEED, MAX_SPEED) else 1f
}
