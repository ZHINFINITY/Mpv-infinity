package app.infinity.mpvz.domain.thumbnail

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore

/** Coalesces equal requests while bounding the number of owned loads. */
internal class ThumbnailRequestCoalescer<K : Any, V>(
  private val scope: CoroutineScope,
  maxConcurrentRequests: Int = 8,
) {
  private val requests = ConcurrentHashMap<K, Deferred<V>>()
  private val slots = Semaphore(maxConcurrentRequests)

  internal val activeRequestCount: Int
    get() = requests.size

  suspend fun getOrLoad(key: K, load: suspend () -> V): V {
    requests[key]?.let { return awaitRequest(key, it) }

    slots.acquire()
    var permitTransferred = false
    try {
      // A request may have been installed while this caller waited for admission.
      requests[key]?.let { return awaitRequest(key, it) }

      val candidate = scope.async(start = CoroutineStart.LAZY) { load() }
      val owned = requests.putIfAbsent(key, candidate)
      if (owned != null) {
        candidate.cancel()
        return awaitRequest(key, owned)
      }

      // From this point the completion handler owns the permit, including when
      // start() observes a scope that was cancelled concurrently.
      permitTransferred = true
      candidate.invokeOnCompletion {
        requests.remove(key, candidate)
        slots.release()
      }
      candidate.start()
      return awaitRequest(key, candidate)
    } finally {
      if (!permitTransferred) {
        slots.release()
      }
    }
  }

  private suspend fun awaitRequest(key: K, request: Deferred<V>): V =
    try {
      request.await()
    } finally {
      // An awaiter can resume before invokeOnCompletion has finished map cleanup. Remove the
      // exact completed request here too, so an immediate retry cannot reuse its old result/error.
      // A canceled UI waiter must never remove a still-running repository-owned operation.
      if (request.isCompleted) requests.remove(key, request)
    }

  /** Cancels current repository-owned loads without cancelling the supplied scope. */
  fun cancelAll() {
    requests.values.toSet().forEach { it.cancel() }
  }
}
