package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSeekDispatchPolicyTest {
  @Test
  fun sustainedDragDispatchesLatestTargetAtBoundedIntervals() {
    val policy = NativeSeekDispatchPolicy()

    assertEquals(100L, policy.request(100L, nowMs = 0L))
    assertNull(policy.request(200L, nowMs = 40L))
    assertEquals(80L, policy.delayUntilDispatch(nowMs = 40L))
    assertNull(policy.request(300L, nowMs = 100L))
    assertEquals(300L, policy.dispatch(nowMs = 120L))
    assertNull(policy.request(400L, nowMs = 160L))
    assertEquals(400L, policy.dispatch(nowMs = 240L))
  }

  @Test
  fun pendingTargetPreservesBackwardAndZeroRequests() {
    val policy = NativeSeekDispatchPolicy()

    assertEquals(1_000L, policy.request(1_000L, nowMs = 0L))
    assertNull(policy.request(0L, nowMs = 1L))
    assertEquals(0L, policy.pendingTarget)
    assertEquals(0L, policy.dispatch(nowMs = 120L))
    assertNull(policy.pendingTarget)
  }

  @Test
  fun resetMakesNextRequestImmediateAndDropsOldTarget() {
    val policy = NativeSeekDispatchPolicy()

    assertEquals(500L, policy.request(500L, nowMs = 0L))
    assertNull(policy.request(600L, nowMs = 10L))
    policy.reset()

    assertNull(policy.pendingTarget)
    assertEquals(0L, policy.request(0L, nowMs = 11L))
  }
}
