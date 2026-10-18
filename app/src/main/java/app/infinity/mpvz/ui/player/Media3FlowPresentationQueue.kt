package app.infinity.mpvz.ui.player

import java.util.ArrayDeque

/**
 * Bounded single-producer/single-consumer handoff for presentation buffers.
 *
 * A frame being displayed is never reused. At most one unconsumed frame is retained; a newer
 * producer frame replaces that pending frame instead of building an unbounded latency queue.
 */
internal class Media3FlowPresentationQueue(capacity: Int) {
  enum class State { FREE, WRITING, READY, PRESENTING, QUARANTINED }

  data class WriteLease(val slot: Int, val replacedPending: Boolean)
  data class WriteAttempt(val lease: WriteLease?, val slotStates: Map<State, Int>)

  init {
    require(capacity >= 2) { "At least one presenting and one writable slot are required" }
  }

  private val states = Array(capacity) { State.FREE }
  private val readySlots = ArrayDeque<Int>(1)
  private var coalescedFrames = 0L

  fun acquireForWrite(): WriteLease? = acquireForWriteWithSnapshot().lease

  /** Returns slot occupancy from the same critical section when no write slot can be acquired. */
  @Synchronized
  fun acquireForWriteWithSnapshot(): WriteAttempt {
    val free = states.indexOfFirst { it == State.FREE }
    if (free >= 0) {
      states[free] = State.WRITING
      return WriteAttempt(WriteLease(free, replacedPending = false), emptyMap())
    }

    // The only slot that may be reclaimed is a READY slot not yet acquired by the presenter.
    val pending = readySlots.pollFirst() ?: return WriteAttempt(null, stateCountsLocked())
    check(states[pending] == State.READY)
    states[pending] = State.WRITING
    coalescedFrames++
    return WriteAttempt(WriteLease(pending, replacedPending = true), emptyMap())
  }

  /** Publishes a completed write and returns a superseded, now-free pending slot if any. */
  @Synchronized
  fun publish(lease: WriteLease): Int? {
    checkState(lease.slot, State.WRITING)
    val superseded = readySlots.pollFirst()
    if (superseded != null) {
      check(states[superseded] == State.READY)
      states[superseded] = State.FREE
      coalescedFrames++
    }
    states[lease.slot] = State.READY
    readySlots.addLast(lease.slot)
    return superseded
  }

  /** Transfers the newest ready slot to the consumer. */
  @Synchronized
  fun takeNext(): Int? {
    val slot = readySlots.pollFirst() ?: return null
    checkState(slot, State.READY)
    states[slot] = State.PRESENTING
    return slot
  }

  @Synchronized
  fun abortWrite(lease: WriteLease) {
    checkState(lease.slot, State.WRITING)
    states[lease.slot] = State.FREE
  }

  /** Releases a slot only after its consumer-side GPU fence has signaled. */
  @Synchronized
  fun finishPresentation(slot: Int) {
    checkState(slot, State.PRESENTING)
    states[slot] = State.FREE
  }

  /** Keeps a slot permanently unavailable when the consumer can no longer prove GPU completion. */
  @Synchronized
  fun quarantinePresentation(slot: Int) {
    checkState(slot, State.PRESENTING)
    states[slot] = State.QUARANTINED
  }

  /** Cancels pending frames; a PRESENTING slot remains owned until its GPU read completes. */
  @Synchronized
  fun cancelReady(): List<Int> {
    val cancelled = readySlots.toList()
    readySlots.clear()
    cancelled.forEach { slot ->
      checkState(slot, State.READY)
      states[slot] = State.FREE
    }
    return cancelled
  }

  /**
   * Non-mutating producer admission: only a completely free slot may be used.
   * READY is deliberately not reclaimable for early admission; the consumer owns
   * presentation ordering and the producer must retain the complete pending frame.
   */
  @Synchronized
  fun canAcceptProduction(): Boolean =
    readySlots.isEmpty() && states.any { it == State.FREE }

  @Synchronized
  fun hasReady(): Boolean = readySlots.isNotEmpty()

  @Synchronized
  fun readyCount(): Int = readySlots.size

  @Synchronized
  fun presentingCount(): Int = states.count { it == State.PRESENTING }

  @Synchronized
  fun hasPresenting(): Boolean = states.any { it == State.PRESENTING }

  @Synchronized
  fun coalescedCount(): Long = coalescedFrames

  @Synchronized
  fun state(slot: Int): State {
    require(slot in states.indices) { "Unknown presentation slot $slot" }
    return states[slot]
  }

  private fun stateCountsLocked(): Map<State, Int> =
    State.values().associateWith { expected -> states.count { it == expected } }

  private fun checkState(slot: Int, expected: State) {
    require(slot in states.indices) { "Unknown presentation slot $slot" }
    check(states[slot] == expected) { "Presentation slot $slot is ${states[slot]}, expected $expected" }
  }
}
