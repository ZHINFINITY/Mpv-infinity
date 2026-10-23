/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCallbackGateTest {
  @Test fun freshGateRejectsCallbacksUntilCorePublication() {
    val gate = NativeCallbackGate()
    assertFalse(gate.tryEnter())
    gate.open()
    assertTrue(gate.tryEnter())
    gate.leave()
    gate.closeAndDrain()
    assertFalse(gate.tryEnter())
  }

  @Test(timeout = 10000) fun retirementDrainsAdmittedCallbackBeforeDestroyAndRejectsNewCallbacks() {
    val gate = NativeCallbackGate()
    gate.open()
    assertTrue(gate.tryEnter())
    val worker = Executors.newSingleThreadExecutor()
    var left = false
    try {
      val retired = worker.submit { gate.closeAndDrain() }
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
      var closed = false
      while (System.nanoTime() < deadline) {
        if (!gate.tryEnter()) { closed = true; break }
        gate.leave()
        Thread.yield()
      }
      assertTrue("retirement must close callback admission", closed)
      assertFalse("retirement must wait for the admitted callback", retired.isDone)
      gate.leave()
      left = true
      retired.get(3, TimeUnit.SECONDS)
      assertFalse(gate.tryEnter())
      gate.open()
      assertTrue(gate.tryEnter())
      gate.leave()
    } finally {
      if (!left) gate.leave()
      worker.shutdownNow()
    }
  }

  @Test(expected = IllegalStateException::class) fun unmatchedLeaveCannotCorruptDrainAccounting() {
    NativeCallbackGate().leave()
  }
}
