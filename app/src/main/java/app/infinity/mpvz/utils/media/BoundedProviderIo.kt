/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded, cancellation-aware lanes for APIs that may block despite running on Dispatchers.IO. */
internal object BoundedProviderIo {
  private val metadataQueries = BoundedLane("mpv-provider-metadata")
  private val legacyDataQueries = BoundedLane("mpv-provider-legacy-data")
  private val providerReads = BoundedLane("mpv-provider-read")
  // Keep the sole worker alive; an idle-timeout race can leave an accepted
  // cleanup queued without a worker to consume it.
  private val cleanupExecutor =
    ThreadPoolExecutor(
      1,
      1,
      30L,
      TimeUnit.SECONDS,
      ArrayBlockingQueue<Runnable>(64),
      ThreadFactory { runnable -> Thread(runnable, "mpv-provider-cleanup").apply { isDaemon = true } },
    )

  fun cleanupAsync(operation: () -> Unit) {
    runCatching { cleanupExecutor.execute { runCatching(operation) } }
  }

  suspend fun <T> query(
    timeoutMs: Long,
    fallback: T,
    onCancellation: () -> Unit = {},
    operation: () -> T,
  ): T = metadataQueries.run(timeoutMs, fallback, onCancellation, operation)

  suspend fun <T> legacyDataQuery(
    timeoutMs: Long,
    fallback: T,
    onCancellation: () -> Unit = {},
    operation: () -> T,
  ): T = legacyDataQueries.run(timeoutMs, fallback, onCancellation, operation)

  suspend fun <T> read(
    timeoutMs: Long,
    fallback: T,
    onCancellation: () -> Unit = {},
    operation: () -> T,
  ): T = providerReads.run(timeoutMs, fallback, onCancellation, operation)

  internal class BoundedLane(threadName: String) {
    // Keep this single daemon worker alive: an expiring core worker can race a new
    // SynchronousQueue submission and reject it while the old worker is exiting.
    private val executor =
      ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
      )

    @OptIn(InternalCoroutinesApi::class)
    suspend fun <T> run(
      timeoutMs: Long,
      fallback: T,
      onCancellation: () -> Unit,
      operation: () -> T,
    ): T =
      withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
        suspendCancellableCoroutine { continuation ->
          val task =
            FutureTask<Unit> {
              val result = try {
                operation()
              } catch (_: Exception) {
                fallback
              }
              continuation.tryResume(result)?.let(continuation::completeResume)
              Unit
            }
          continuation.invokeOnCancellation {
            task.cancel(true)
            cleanupAsync(onCancellation)
          }
          try {
            executor.execute(task)
          } catch (_: RejectedExecutionException) {
            continuation.tryResume(fallback)?.let(continuation::completeResume)
          }
        }
      } ?: fallback
  }
}
