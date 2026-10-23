/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Admission fence used while retiring a native core; destruction drains admitted callbacks. */
internal class NativeCallbackGate {
  private val lock = ReentrantLock(true)
  private val drained: Condition = lock.newCondition()
  private var open = false
  private var admitted = 0

  fun open() {
    lock.withLock { check(admitted == 0); open = true }
  }
  fun tryEnter(): Boolean = lock.withLock { if (!open) false else { admitted++; true } }
  fun leave() = lock.withLock {
    check(admitted > 0) { "Native callback admission underflow" }
    admitted--
    if (admitted == 0) drained.signalAll()
  }
  fun closeAndDrain() = lock.withLock {
    open = false
    while (admitted != 0) drained.awaitUninterruptibly()
  }
}
