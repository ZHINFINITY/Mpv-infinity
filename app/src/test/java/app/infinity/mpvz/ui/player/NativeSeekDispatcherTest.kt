package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSeekDispatcherTest {
  @Test
  fun continuousDragDispatchesLatestTargetEvery100msWithoutWaitingForRelease() {
    val fake = FakeTime()
    val events = mutableListOf<Pair<Long, Boolean>>()
    val dispatcher = fake.dispatcher { position, committed -> events += position to committed }
    dispatcher.preview(100)
    fake.advanceTo(0)
    assertEquals(listOf(100L to false), events)

    for (time in 10L..90L step 10) {
      fake.advanceTo(time)
      dispatcher.preview(time * 10)
    }
    assertEquals(listOf(0L, 100L), fake.scheduledAt)
    fake.advanceTo(100)
    assertEquals(listOf(100L to false, 900L to false), events)
    assertNull(dispatcher.pendingPositionMs)

    fake.advanceTo(110)
    dispatcher.preview(1100)
    fake.advanceTo(199)
    dispatcher.preview(1990)
    fake.advanceTo(200)
    assertEquals(1990L to false, events.last())
  }

  @Test
  fun releaseCancelsOlderPreviewAndImmediatelyDispatchesFinalTarget() {
    val fake = FakeTime()
    val events = mutableListOf<Pair<Long, Boolean>>()
    val dispatcher = fake.dispatcher { position, committed -> events += position to committed }
    dispatcher.preview(100)
    fake.advanceTo(0)
    fake.advanceTo(20)
    dispatcher.preview(200)
    dispatcher.commit(250)
    fake.advanceTo(1000)
    assertEquals(listOf(100L to false, 250L to true), events)
    assertNull(dispatcher.pendingPositionMs)
  }

  @Test
  fun cancelPreventsOldMediaSeekAndResetsThrottleForNextMedia() {
    val fake = FakeTime()
    val events = mutableListOf<Pair<Long, Boolean>>()
    val dispatcher = fake.dispatcher { position, committed -> events += position to committed }
    dispatcher.commit(100)
    fake.advanceTo(20)
    dispatcher.preview(200)
    dispatcher.cancel()
    dispatcher.preview(800)
    fake.advanceTo(20)
    assertEquals(listOf(100L to true, 800L to false), events)
    fake.advanceTo(1000)
    assertEquals(2, events.size)
  }

  @Test
  fun onlyUndispatchedTargetsRemainAvailableForRelativeSeekChaining() {
    val fake = FakeTime()
    val dispatcher = fake.dispatcher { _, _ -> }
    dispatcher.preview(100)
    dispatcher.preview((dispatcher.pendingPositionMs ?: 0L) + 50)
    assertEquals(150L, dispatcher.pendingPositionMs)
    fake.advanceTo(0)
    assertNull(dispatcher.pendingPositionMs)
  }

  @Test
  fun targetsAreClampedToNonnegative() {
    val fake = FakeTime()
    val events = mutableListOf<Pair<Long, Boolean>>()
    val dispatcher = fake.dispatcher { position, committed -> events += position to committed }
    dispatcher.preview(-10)
    assertEquals(0L, dispatcher.pendingPositionMs)
    fake.advanceTo(0)
    dispatcher.commit(-20)
    assertEquals(listOf(0L to false, 0L to true), events)
  }

  private class FakeTime {
    private var now = 0L
    val scheduledAt = mutableListOf<Long>()
    private val queue = mutableListOf<Pair<Runnable, Long>>()

    fun dispatcher(dispatch: (Long, Boolean) -> Unit) = NativeSeekDispatcher(
      nowMs = { now },
      postDelayed = { task, delay ->
        scheduledAt += now + delay
        queue += task to (now + delay)
      },
      removeCallbacks = { task -> queue.removeAll { it.first === task }; Unit },
      dispatch = dispatch,
    )

    fun advanceTo(target: Long) {
      require(target >= now)
      while (true) {
        val next = queue.filter { it.second <= target }.minByOrNull { it.second } ?: break
        queue.remove(next)
        now = next.second
        next.first.run()
      }
      now = target
    }
  }
}
