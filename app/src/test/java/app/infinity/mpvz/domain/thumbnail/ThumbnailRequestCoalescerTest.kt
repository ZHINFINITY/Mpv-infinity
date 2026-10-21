package app.infinity.mpvz.domain.thumbnail

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailRequestCoalescerTest {
  @Test
  fun canceledUiWaiterDoesNotCancelOperationAndSecondAwaitJoinsOneLoad() = runBlocking {
    val owner = SupervisorJob()
    val scope = CoroutineScope(owner + Dispatchers.Default)
    try {
      val coalescer = ThumbnailRequestCoalescer<String, String>(scope)
      val started = CompletableDeferred<Unit>()
      val finish = CompletableDeferred<String>()
      var loads = 0
      val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        coalescer.getOrLoad("key") {
          loads++
          started.complete(Unit)
          finish.await()
        }
      }
      withTimeout(5_000) { started.await() }
      first.cancelAndJoin()
      val second = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        coalescer.getOrLoad("key") { error("duplicate load") }
      }
      finish.complete("value")
      assertEquals("value", withTimeout(5_000) { second.await() })
      assertEquals(1, loads)
    } finally {
      owner.cancel()
    }
  }

  @Test
  fun equalKeysCoalesce() = runBlocking {
    val owner = SupervisorJob()
    val scope = CoroutineScope(owner + Dispatchers.Default)
    try {
      val coalescer = ThumbnailRequestCoalescer<String, Int>(scope)
      val started = CompletableDeferred<Unit>()
      val finish = CompletableDeferred<Int>()
      var loads = 0
      suspend fun load(): Int {
        loads++
        started.complete(Unit)
        return finish.await()
      }
      val first = async { coalescer.getOrLoad("same", ::load) }
      started.await()
      val second = async { coalescer.getOrLoad("same", ::load) }
      finish.complete(7)
      assertEquals(7, first.await())
      assertEquals(7, second.await())
      assertEquals(1, loads)
    } finally {
      owner.cancel()
    }
  }

  @Test
  fun admissionIsBoundedAndCanceledPendingWaitersDoNotStartLoads() = runBlocking {
    val owner = SupervisorJob()
    val scope = CoroutineScope(owner + Dispatchers.Default)
    try {
      val coalescer = ThumbnailRequestCoalescer<Int, Int>(scope, maxConcurrentRequests = 2)
      val gate = CompletableDeferred<Unit>()
      val admitted = List(2) { CompletableDeferred<Unit>() }
      val running = AtomicInteger(0)
      val maxRunning = AtomicInteger(0)
      val loads = AtomicInteger(0)
      val calls = List(6) { key ->
        async {
          coalescer.getOrLoad(key) {
            val now = running.incrementAndGet()
            loads.incrementAndGet()
            maxRunning.updateAndGet { old -> maxOf(old, now) }
            if (now <= 2) admitted[now - 1].complete(Unit)
            try {
              gate.await()
              key
            } finally {
              running.decrementAndGet()
            }
          }
        }
      }
      admitted.forEach { withTimeout(5_000) { it.await() } }
      calls.drop(2).forEach { it.cancelAndJoin() }
      assertEquals(2, coalescer.activeRequestCount)
      assertEquals(2, maxRunning.get())
      coalescer.cancelAll()
      calls.take(2).forEach { it.cancelAndJoin() }
      assertEquals(2, loads.get())
    } finally {
      owner.cancel()
    }
  }

  @Test
  fun failedLoadIsRemovedAndCanBeRetried() = runBlocking {
    val owner = SupervisorJob()
    val scope = CoroutineScope(owner + Dispatchers.Default)
    try {
      val coalescer = ThumbnailRequestCoalescer<String, Int>(scope)
      var attempts = 0
      var failed = false
      try {
        coalescer.getOrLoad("retry") {
          attempts++
          error("expected")
        }
      } catch (_: IllegalStateException) {
        failed = true
      }
      assertTrue(failed)
      assertEquals(0, coalescer.activeRequestCount)
      assertEquals(9, coalescer.getOrLoad("retry") { attempts++; 9 })
      assertEquals(2, attempts)
    } finally {
      owner.cancel()
    }
  }

  @Test
  fun cancelAllReleasesRequestsAndFutureLoadsWork() = runBlocking {
    val owner = SupervisorJob()
    val scope = CoroutineScope(owner + Dispatchers.Default)
    try {
      val coalescer = ThumbnailRequestCoalescer<String, Int>(scope, maxConcurrentRequests = 1)
      val started = CompletableDeferred<Unit>()
      val first = launch {
        coalescer.getOrLoad("old") {
          started.complete(Unit)
          CompletableDeferred<Unit>().await()
          error("unreachable")
        }
      }
      started.await()
      coalescer.cancelAll()
      first.join()
      assertEquals(0, coalescer.activeRequestCount)
      assertFalse(owner.isCancelled)
      assertEquals(3, coalescer.getOrLoad("new") { 3 })
    } finally {
      owner.cancel()
    }
  }
}
