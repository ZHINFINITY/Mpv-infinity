package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3FlowPresentationQueueTest {
  @Test
  fun newestPendingFrameReplacesOlderReadyFrame() {
    val queue = Media3FlowPresentationQueue(capacity = 2)
    val first = queue.acquireForWrite() ?: error("first write lease unavailable")
    queue.publish(first)
    val second = queue.acquireForWrite() ?: error("second write lease unavailable")
    val superseded = queue.publish(second)

    assertEquals(first.slot, superseded)
    assertEquals(1L, queue.coalescedCount())
    assertEquals(Media3FlowPresentationQueue.State.FREE, queue.state(first.slot))
    assertEquals(second.slot, queue.takeNext())
  }

  @Test
  fun activePresentationIsNeverReclaimedAndCapacityStaysBounded() {
    val queue = Media3FlowPresentationQueue(capacity = 2)
    val active = queue.acquireForWrite() ?: error("active write lease unavailable")
    queue.publish(active)
    assertEquals(active.slot, queue.takeNext())

    val pending = queue.acquireForWrite() ?: error("pending write lease unavailable")
    queue.publish(pending)
    val replacement = queue.acquireForWrite()
    assertNotNull(replacement)
    assertEquals(pending.slot, replacement?.slot)
    assertTrue(replacement?.replacedPending == true)
    assertNull(queue.acquireForWrite())
    assertEquals(Media3FlowPresentationQueue.State.PRESENTING, queue.state(active.slot))
  }

  @Test
  fun cancelReadyLeavesPresentingSlotOwnedUntilCompletion() {
    val queue = Media3FlowPresentationQueue(capacity = 2)
    val active = queue.acquireForWrite() ?: error("active write lease unavailable")
    queue.publish(active)
    queue.takeNext()
    val pending = queue.acquireForWrite() ?: error("pending write lease unavailable")
    queue.publish(pending)

    assertEquals(listOf(pending.slot), queue.cancelReady())
    assertFalse(queue.hasReady())
    assertEquals(Media3FlowPresentationQueue.State.PRESENTING, queue.state(active.slot))
    assertEquals(Media3FlowPresentationQueue.State.FREE, queue.state(pending.slot))
    queue.finishPresentation(active.slot)
    assertEquals(Media3FlowPresentationQueue.State.FREE, queue.state(active.slot))
  }

  @Test
  fun abortedWriteCanBeReusedWithoutPublishingIt() {
    val queue = Media3FlowPresentationQueue(capacity = 2)
    val lease = queue.acquireForWrite() ?: error("write lease unavailable")
    queue.abortWrite(lease)

    assertEquals(Media3FlowPresentationQueue.State.FREE, queue.state(lease.slot))
    assertNull(queue.takeNext())
    assertEquals(lease.slot, queue.acquireForWrite()?.slot)
  }

  @Test
  fun quarantinedPresentationIsNeverReusedAndDoesNotBlockReleaseDetection() {
    val queue = Media3FlowPresentationQueue(capacity = 2)
    val lease = queue.acquireForWrite() ?: error("write lease unavailable")
    queue.publish(lease)
    val slot = queue.takeNext() ?: error("ready slot unavailable")

    queue.quarantinePresentation(slot)

    assertEquals(Media3FlowPresentationQueue.State.QUARANTINED, queue.state(slot))
    assertFalse(queue.hasPresenting())
    val remainingLease = queue.acquireForWrite() ?: error("other bounded slot unavailable")
    assertTrue(remainingLease.slot != slot)
    queue.abortWrite(remainingLease)
  }

  @Test
  fun quarantiningActiveSlotLeavesPendingFrameCancellableOnFailure() {
    val queue = Media3FlowPresentationQueue(capacity = 2)
    val first = queue.acquireForWrite() ?: error("first write lease unavailable")
    queue.publish(first)
    val active = queue.takeNext() ?: error("first ready slot unavailable")
    val second = queue.acquireForWrite() ?: error("second write lease unavailable")
    queue.publish(second)
    assertTrue(queue.hasPresenting())
    assertEquals(Media3FlowPresentationQueue.State.READY, queue.state(second.slot))

    queue.quarantinePresentation(active)

    assertFalse(queue.hasPresenting())
    assertEquals(listOf(second.slot), queue.cancelReady())
    assertEquals(Media3FlowPresentationQueue.State.QUARANTINED, queue.state(active))
    assertEquals(Media3FlowPresentationQueue.State.FREE, queue.state(second.slot))
    val reusable = queue.acquireForWrite() ?: error("cancelled pending slot should be reusable")
    assertEquals(second.slot, reusable.slot)
    queue.abortWrite(reusable)
  }
}
