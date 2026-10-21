/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayerSheetLayoutPolicyTest {
  @Test fun portraitSheetsReserveNinetyPercentWhenKeyboardIsHidden() {
    assertEquals(864f, PlayerSheetLayoutPolicy.maxHeightDp(960, portrait = true, imeVisible = false, customMaxHeightDp = null), 0.001f)
  }

  @Test fun keyboardGetsTheFullWindowAndDoesNotClipTheTopRow() {
    assertEquals(960f, PlayerSheetLayoutPolicy.maxHeightDp(960, portrait = true, imeVisible = true, customMaxHeightDp = null), 0.001f)
  }

  @Test fun explicitPlaylistHeightWinsOverOrientationDefaults() {
    assertEquals(480f, PlayerSheetLayoutPolicy.maxHeightDp(960, portrait = true, imeVisible = false, customMaxHeightDp = 480f), 0.001f)
  }

  @Test fun invalidWindowSizeIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      PlayerSheetLayoutPolicy.maxHeightDp(0, portrait = true, imeVisible = false, customMaxHeightDp = null)
    }
  }
}
