/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded, cancellation-aware lanes for APIs that may block despite running on Dispatchers.IO. */
internal object BoundedProviderIo {
  internal enum class ReadStatus { COMPLETED, TIMED_OUT, SATURATED, FAILED }

  internal data class ReadResult<T>(
    val status: ReadStatus,
    val value: T,
    val error: Exception? = null,
  )

  private val metadataQueries = BoundedLane("mpv-provider-metadata")
  private val legacyDataQueries = BoundedLane("mpv-provider-legacy-data")
  // Two independent workers let one stuck provider read time out without poisoning every later read.
  // A SynchronousQueue keeps total concurrency strictly bounded and reports saturation immediately.
  private val providerReads = BoundedLane("mpv-provider-read", workerCount = 2)
  private val cleanupLane = CleanupLane("mpv-provider-cleanup", workerCount = 2, queueCapacity = 64)

  /** Returns false and reports the rejection when the bounded cleanup lane is saturated. */
  fun cleanupAsync(operation: () -> Unit): Boolean = cleanupLane.submit(operation)

  internal fun cleanupRejectionCount(): Long = cleanupLane.rejectionCount()

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
  ): T = readWithStatus(timeoutMs, fallback, onCancellation, operation).value

  suspend fun <T> readWithStatus(
    timeoutMs: Long,
    fallback: T,
    onCancellation: () -> Unit = {},
    operation: () -> T,
  ): ReadResult<T> = providerReads.runWithStatus(timeoutMs, fallback, onCancellation, operation)

  internal class BoundedLane(
    threadName: String,
    workerCount: Int = 1,
  ) {
    private val executor =
      ThreadPoolExecutor(
        workerCount,
        workerCount,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(),
        ThreadFactory { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
      )

    suspend fun <T> run(
      timeoutMs: Long,
      fallback: T,
      onCancellation: () -> Unit = {},
      operation: () -> T,
    ): T = runWithStatus(timeoutMs, fallback, onCancellation, operation).value

    @OptIn(InternalCoroutinesApi::class)
    suspend fun <T> runWithStatus(
      timeoutMs: Long,
      fallback: T,
      onCancellation: () -> Unit = {},
      operation: () -> T,
    ): ReadResult<T> {
      val outcome =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
          suspendCancellableCoroutine { continuation ->
            val task =
              FutureTask<Unit> {
                val result =
                  try {
                    ReadResult(ReadStatus.COMPLETED, operation())
                  } catch (error: Exception) {
                    ReadResult(ReadStatus.FAILED, fallback, error)
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
              continuation.tryResume(ReadResult(ReadStatus.SATURATED, fallback))
                ?.let(continuation::completeResume)
            }
          }
        }
      return outcome ?: ReadResult(ReadStatus.TIMED_OUT, fallback)
    }
  }

  /** Small fixed worker pool with a bounded queue; rejection is observable and counted. */
  internal class CleanupLane(
    threadName: String,
    workerCount: Int,
    queueCapacity: Int,
  ) {
    private val rejections = AtomicLong(0L)
    private val failures = AtomicLong(0L)
    private val queue: BlockingQueue<Runnable> =
      if (queueCapacity == 0) SynchronousQueue() else ArrayBlockingQueue(queueCapacity)
    private val executor =
      ThreadPoolExecutor(
        workerCount,
        workerCount,
        30L,
        TimeUnit.SECONDS,
        queue,
        ThreadFactory { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
      )

    fun submit(operation: () -> Unit): Boolean =
      try {
        executor.execute {
          try {
            operation()
          } catch (error: Exception) {
            failures.incrementAndGet()
            System.err.println("BoundedProviderIo cleanup operation failed: ${error.message}")
          }
        }
        true
      } catch (_: RejectedExecutionException) {
        rejections.incrementAndGet()
        System.err.println("BoundedProviderIo cleanup lane saturated; cleanup was not accepted")
        false
      }

    fun rejectionCount(): Long = rejections.get()

    internal fun failureCount(): Long = failures.get()
  }
}
