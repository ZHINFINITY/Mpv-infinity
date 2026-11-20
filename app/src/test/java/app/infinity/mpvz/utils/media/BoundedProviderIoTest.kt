/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedProviderIoTest {
  @Test
  fun blockedProviderOperationReturnsTheFallbackWithinItsDeadline() = runBlocking {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    try {
      val result =
        BoundedProviderIo.query(timeoutMs = 50L, fallback = "fallback") {
          started.countDown()
          release.await(2, TimeUnit.SECONDS)
          "late result"
        }
      assertTrue(started.await(1, TimeUnit.SECONDS))
      assertEquals("fallback", result)
    } finally {
      release.countDown()
    }
  }

  @Test
  fun cancellingProviderReadSchedulesResourceCleanup() = runBlocking {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val cleaned = CountDownLatch(1)
    // Parallel M3U tests may use the production lane; isolate this cancellation test.
    val lane = BoundedProviderIo.BoundedLane("mpv-provider-cancellation-test")
    val job = launch {
      lane.run(
        timeoutMs = 10_000L,
        fallback = Unit,
        onCancellation = { cleaned.countDown() },
      ) {
        started.countDown()
        release.await(2, TimeUnit.SECONDS)
        Unit
      }
    }
    assertTrue(started.await(1, TimeUnit.SECONDS))
    job.cancelAndJoin()
    assertTrue(cleaned.await(1, TimeUnit.SECONDS))
    release.countDown()
  }
}
