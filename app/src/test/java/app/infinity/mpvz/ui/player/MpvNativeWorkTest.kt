/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0 */
package app.infinity.mpvz.ui.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvNativeWorkTest {
  @Test(timeout = 10000) fun blockedNativeWorkDoesNotBlockCallerHeartbeat() = runBlocking {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
      val worker = MpvNativeWork(dispatcher)
      val started = CountDownLatch(1)
      val release = CountDownLatch(1)
      val caller = Thread.currentThread().id
      val native = async {
        worker.execute {
          assertTrue(Thread.currentThread().id != caller)
          started.countDown()
          assertTrue(release.await(5, TimeUnit.SECONDS))
          120
        }
      }
      try {
        // Yield the caller so execute() can dispatch its genuinely blocking native simulation.
        delay(1)
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val heartbeat = async { delay(10); "responsive" }
        assertEquals("responsive", heartbeat.await())
        assertFalse(native.isCompleted)
      } finally {
        release.countDown()
      }
      assertEquals(120, native.await())
    }
  }

  @Test(timeout = 10000) fun concurrentNativeRequestsAreSerializedEvenWithMultipleWorkerThreads() = runBlocking {
    Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { dispatcher ->
      val worker = MpvNativeWork(dispatcher)
      val inFlight = AtomicInteger()
      val peak = AtomicInteger()
      val requests = (1..12).map { value ->
        async {
          worker.execute {
            val active = inFlight.incrementAndGet()
            peak.updateAndGet { old -> maxOf(old, active) }
            try { delay(2); value } finally { inFlight.decrementAndGet() }
          }
        }
      }
      assertEquals((1..12).toList(), requests.map { it.await() })
      assertEquals(1, peak.get())
      assertEquals(0, inFlight.get())
    }
  }

  @Test(timeout = 10000) fun cancellationReleasesTheProductionWorker() = runBlocking {
    val worker = MpvNativeWork()
    val started = CompletableDeferred<Unit>()
    val request = async {
      worker.execute<Unit> { started.complete(Unit); awaitCancellation() }
    }
    started.await()
    request.cancelAndJoin()
    assertEquals(120, worker.execute { 120 })
  }

  @Test fun failureReleasesTheWorkerForTheNextNativeRequest() = runBlocking {
    val worker = MpvNativeWork()
    var failure: Throwable? = null
    try { worker.execute<Unit> { error("native failure") } } catch (error: IllegalStateException) { failure = error }
    assertEquals("native failure", failure?.message)
    assertEquals(240, worker.execute { 240 })
  }
}
