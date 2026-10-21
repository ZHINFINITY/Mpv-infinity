/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import app.infinity.mpvz.ui.player.controls.PlayerControlGridPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlGridPolicyTest {
  @Test fun rowsAreDeterministicForPartialLastRow() {
    assertEquals(0, PlayerControlGridPolicy.rowCount(0))
    assertEquals(1, PlayerControlGridPolicy.rowCount(1))
    assertEquals(1, PlayerControlGridPolicy.rowCount(3))
    assertEquals(2, PlayerControlGridPolicy.rowCount(4))
    assertEquals(3, PlayerControlGridPolicy.rowCount(7))
  }
}
