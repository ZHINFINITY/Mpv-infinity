/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.ytdlp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtdlpPageResolveBudgetTest {
  @Test
  fun recursiveResolutionCannotExceedItsSharedRequestBudget() {
    val budget = YtdlpPageResolveBudget(maximumRequests = 2)

    assertTrue(budget.reserveRequest())
    assertTrue(budget.reserveRequest())
    assertFalse(budget.reserveRequest())
  }
}
