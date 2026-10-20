/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedGesturePolicyTest {
  @Test
  fun entersWithOneTargetCommandInsteadOfARamp() {
    assertEquals(
      listOf(SpeedGestureCommand(2f)),
      SpeedGesturePolicy.enter(originalSpeed = 1.25f),
    )
  }

  @Test
  fun restoresTheCapturedPreferenceRatherThanAssumingNormalSpeed() {
    assertEquals(
      listOf(SpeedGestureCommand(1.5f)),
      SpeedGesturePolicy.restore(originalSpeed = 1.5f, locked = false),
    )
  }

  @Test
  fun lockedHoldDoesNotWriteARestorationCommand() {
    assertTrue(SpeedGesturePolicy.restore(originalSpeed = 1.5f, locked = true).isEmpty())
  }

  @Test
  fun alreadyAtTargetDoesNotRepeatTheSpeedWrite() {
    assertTrue(SpeedGesturePolicy.enter(originalSpeed = 2f).isEmpty())
  }

  @Test
  fun nonFiniteAndOutOfRangeValuesAreSafeForTheEngine() {
    assertEquals(
      listOf(SpeedGestureCommand(1f)),
      SpeedGesturePolicy.restore(originalSpeed = Float.NaN, locked = false),
    )
    assertEquals(
      listOf(SpeedGestureCommand(8f)),
      SpeedGesturePolicy.restore(originalSpeed = 20f, locked = false),
    )
  }
}
