/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedProviderIoTest {
  @Test
  fun timeoutReturnsWhileDedicatedWorkerStillIgnoresInterrupts() = runBlocking {
    val lane = BoundedProviderIo.BoundedLane("mpv-provider-timeout-test")
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workerFinished = CountDownLatch(1)
    val caller = async(Dispatchers.Default) {
      lane.runWithStatus(timeoutMs = 50L, fallback = "fallback") {
        started.countDown()
        waitIgnoringInterrupt(release)
        workerFinished.countDown()
        "late result"
      }
    }
    try {
      assertTrue(started.await(1, TimeUnit.SECONDS))
      val result = caller.await()
      assertEquals(BoundedProviderIo.ReadStatus.TIMED_OUT, result.status)
      assertEquals("fallback", result.value)
      assertFalse("provider worker should still be blocked after caller timeout", workerFinished.await(20, TimeUnit.MILLISECONDS))
    } finally {
      release.countDown()
      caller.cancelAndJoin()
    }
    assertTrue(workerFinished.await(1, TimeUnit.SECONDS))
  }

  @Test
  fun cancellingProviderReadSignalsImmediatelyAndSchedulesResourceCleanup() = runBlocking {
    val lane = BoundedProviderIo.BoundedLane("mpv-provider-cancellation-test")
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val cancellationRequested = CountDownLatch(1)
    val cleaned = CountDownLatch(1)
    val job = launch(Dispatchers.Default) {
      lane.runWithStatus(
        timeoutMs = 10_000L,
        fallback = Unit,
        onCancellation = { cleaned.countDown() },
        onCancellationRequested = { cancellationRequested.countDown() },
      ) {
        started.countDown()
        waitIgnoringInterrupt(release)
        Unit
      }
    }
    try {
      assertTrue(started.await(1, TimeUnit.SECONDS))
      job.cancelAndJoin()
      assertTrue(cancellationRequested.await(1, TimeUnit.SECONDS))
      assertTrue(cleaned.await(1, TimeUnit.SECONDS))
    } finally {
      release.countDown()
    }
  }

  @Test
  fun oneStalledProviderReadDoesNotStarveAnotherWorker() = runBlocking {
    val lane = BoundedProviderIo.BoundedLane("mpv-provider-parallel-test", workerCount = 2)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val stalled = launch(Dispatchers.Default) {
      lane.runWithStatus(timeoutMs = 10_000L, fallback = -1) {
        started.countDown()
        waitIgnoringInterrupt(release)
        1
      }
    }
    try {
      assertTrue(started.await(1, TimeUnit.SECONDS))
      val healthy = lane.runWithStatus(timeoutMs = 500L, fallback = -1) { 2 }
      assertEquals(BoundedProviderIo.ReadStatus.COMPLETED, healthy.status)
      assertEquals(2, healthy.value)
    } finally {
      release.countDown()
      stalled.cancelAndJoin()
    }
  }

  @Test
  fun saturatedProviderLaneReportsSaturationInsteadOfAFalseTimeout() = runBlocking {
    val lane = BoundedProviderIo.BoundedLane("mpv-provider-saturation-test", workerCount = 2)
    val started = CountDownLatch(2)
    val release = CountDownLatch(1)
    fun blockedRead() {
      started.countDown()
      waitIgnoringInterrupt(release)
    }
    val first = launch(Dispatchers.Default) {
      lane.runWithStatus(timeoutMs = 10_000L, fallback = Unit) { blockedRead() }
    }
    val second = launch(Dispatchers.Default) {
      lane.runWithStatus(timeoutMs = 10_000L, fallback = Unit) { blockedRead() }
    }
    try {
      assertTrue(started.await(1, TimeUnit.SECONDS))
      val saturated = lane.runWithStatus(timeoutMs = 500L, fallback = "unavailable") { "unexpected" }
      assertEquals(BoundedProviderIo.ReadStatus.SATURATED, saturated.status)
      assertEquals("unavailable", saturated.value)
    } finally {
      release.countDown()
      first.cancelAndJoin()
      second.cancelAndJoin()
    }
  }

  @Test
  fun blockedCleanupDoesNotStarveIndependentCleanup() {
    val lane = BoundedProviderIo.CleanupLane("mpv-provider-cleanup-isolation-test", workerCount = 2, queueCapacity = 2)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val secondCleaned = CountDownLatch(1)
    try {
      assertTrue(lane.submit {
        started.countDown()
        waitIgnoringInterrupt(release)
      })
      assertTrue(started.await(1, TimeUnit.SECONDS))
      assertTrue(lane.submit { secondCleaned.countDown() })
      assertTrue("second cleanup should run on an independent worker", secondCleaned.await(1, TimeUnit.SECONDS))
    } finally {
      release.countDown()
    }
  }

  @Test
  fun cleanupSaturationIsCountedAndReturnedToCaller() {
    val lane = BoundedProviderIo.CleanupLane("mpv-provider-cleanup-saturation-test", workerCount = 1, queueCapacity = 0)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    try {
      assertTrue(lane.submit {
        started.countDown()
        waitIgnoringInterrupt(release)
      })
      assertTrue(started.await(1, TimeUnit.SECONDS))
      assertFalse(lane.submit { error("rejected cleanup must not run") })
      assertEquals(1L, lane.rejectionCount())
    } finally {
      release.countDown()
    }
  }

  private fun waitIgnoringInterrupt(release: CountDownLatch) {
    while (release.count > 0L) {
      try {
        release.await(25L, TimeUnit.MILLISECONDS)
      } catch (_: InterruptedException) {
        // Deliberately model ContentProviders that ignore cancellation/interruption.
      }
    }
  }
}
