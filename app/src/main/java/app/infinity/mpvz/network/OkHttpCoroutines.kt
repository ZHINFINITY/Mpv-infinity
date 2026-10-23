/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

internal suspend fun Call.awaitResponse(): Response =
  suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          continuation.resume(response) { _, rejectedResponse, _ -> rejectedResponse.close() }
        }
      },
    )
  }
/** Status and fully read body of a response; the connection is already released. */
internal class HttpTextResponse(
  val code: Int,
  val bodyText: String,
) {
  val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Runs [block] on the response inside OkHttp's callback while the caller stays suspended,
 * so cancelling the coroutine cancels the call even while the body is still being read.
 *
 * Unlike `execute()`, a cancelled caller is released immediately: the callback drops its
 * continuation on cancellation, so neither a dispatcher worker nor an OkHttp thread keeps
 * the caller (for example a ViewModel captured by a coroutine lambda) reachable.
 */
internal suspend fun <T> Call.awaitUse(block: (Response) -> T): T =
  suspendCancellableCoroutine { continuation ->
    val pending = java.util.concurrent.atomic.AtomicReference(continuation)
    continuation.invokeOnCancellation {
      pending.set(null)
      cancel()
    }
    enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          pending.getAndSet(null)?.resumeWith(Result.failure(e))
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          val result = runCatching { response.use(block) }
          pending.getAndSet(null)?.resumeWith(result)
        }
      },
    )
  }

/** [awaitUse] that reads the whole body as text. */
internal suspend fun Call.awaitText(): HttpTextResponse = awaitUse { response -> HttpTextResponse(response.code, response.body.string()) }
