/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player.ytdlp

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YtdlpPageResolveBudgetTest {
  @Test
  fun optionalPageReadFallsBackOnBodyIoFailure() {
    assertNull(withOptionalPageRead<String> { throw IOException("body reset") })
  }

  @Test
  fun optionalPageReadPropagatesCancellation() {
    val cancellation = CancellationException("request cancelled")
    try {
      withOptionalPageRead<String> { throw cancellation }
      fail("cancellation must not be converted into an optional fallback")
    } catch (actual: CancellationException) {
      assertSame(cancellation, actual)
    }
  }

  @Test
  fun recursiveResolutionCannotExceedItsSharedRequestBudget() {
    val budget = YtdlpPageResolveBudget(maximumRequests = 2)

    assertTrue(budget.reserveRequest())
    assertTrue(budget.reserveRequest())
    assertFalse(budget.reserveRequest())
  }
}
