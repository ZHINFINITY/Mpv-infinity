package app.infinity.mpvz.catalog.nuvio.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRuntimeConcurrencyTest {
  @Test
  fun providerTimeoutDoesNotExpireWhileWaitingForRuntimeSlot() = runBlocking {
    val semaphore = Semaphore(1)
    semaphore.acquire()
    val pending = async(Dispatchers.Default) {
      withRuntimeSlotTimeout(semaphore, Dispatchers.Default, 60L) { "completed" }
    }

    delay(120L)
    assertFalse(pending.isCompleted)
    semaphore.release()
    assertEquals("completed", pending.await())
  }

  @Test
  fun providerTimeoutStillAppliesAfterRuntimeSlotAdmission() = runBlocking {
    var timedOut = false
    try {
      withRuntimeSlotTimeout(Semaphore(1), Dispatchers.Default, 60L) {
        delay(200L)
      }
    } catch (_: TimeoutCancellationException) {
      timedOut = true
    }

    assertTrue(timedOut)
  }
}
