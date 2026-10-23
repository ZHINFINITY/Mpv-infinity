/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import app.infinity.mpvz.ui.player.controls.PlayerControlGridPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlGridPolicyTest {
  @Test fun panelWidthCannotExceedItsParentOrLandscapeCap() {
    assertEquals(320f, PlayerControlGridPolicy.panelWidthDp(320f), 0f)
    assertEquals(380f, PlayerControlGridPolicy.panelWidthDp(1000f), 0f)
    assertEquals(0f, PlayerControlGridPolicy.panelWidthDp(-5f), 0f)
    assertEquals(380f, PlayerControlGridPolicy.panelWidthDp(Float.NaN), 0f)
    assertEquals(380f, PlayerControlGridPolicy.panelWidthDp(Float.POSITIVE_INFINITY), 0f)
  }

  @Test fun rowsAreDeterministicForPartialLastRow() {
    assertEquals(0, PlayerControlGridPolicy.rowCount(0))
    assertEquals(1, PlayerControlGridPolicy.rowCount(1))
    assertEquals(1, PlayerControlGridPolicy.rowCount(3))
    assertEquals(2, PlayerControlGridPolicy.rowCount(4))
    assertEquals(3, PlayerControlGridPolicy.rowCount(7))
  }
}
