/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Regression for the LeakCanary report on build 1902: a blocking `execute()` inside
 * `viewModelScope.launch(Dispatchers.IO)` kept `DefaultDispatcher-worker` holding the
 * translation lambda (and so `PlayerViewModel`) after `onCleared()` until the network returned.
 */
class OkHttpAwaitCancellationTest {
  private lateinit var server: MockWebServer
  private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun call() = client.newCall(Request.Builder().url(server.url("/translate")).build())

  @Test
  fun successKeepsStatusAndBody() =
    runBlocking {
      server.enqueue(MockResponse().setBody("hello"))
      val response = call().awaitText()
      assertTrue(response.isSuccessful)
      assertEquals(200, response.code)
      assertEquals("hello", response.bodyText)
    }

  @Test
  fun httpErrorKeepsCodeAndBodyWithoutThrowing() =
    runBlocking {
      server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"bad key"}"""))
      val response = call().awaitText()
      assertFalse(response.isSuccessful)
      assertEquals(403, response.code)
      assertEquals("""{"error":"bad key"}""", response.bodyText)
    }

  @Test
  fun cancellingWhileWaitingForHeadersEndsPromptly() =
    runBlocking {
      server.enqueue(MockResponse().setBody("late").setHeadersDelay(30, TimeUnit.SECONDS))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      var completed: HttpTextResponse? = null
      val job = scope.launch { completed = call().awaitText() }
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
      val started = System.nanoTime()
      job.cancel()
      // A blocking execute() would hold the worker for the full 30 s header delay.
      withTimeout(3_000) { job.join() }
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
      assertTrue(job.isCancelled)
      assertNull(completed)
      scope.cancel()
    }

  @Test
  fun cancellingWhileBodyIsStreamingEndsPromptly() =
    runBlocking {
      // Headers arrive at once; the body trickles at 1 byte/s, so only call.cancel() can stop the read.
      server.enqueue(MockResponse().setBody("x".repeat(64)).throttleBody(1, 1, TimeUnit.SECONDS))
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      val job = scope.launch { call().awaitText() }
      assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
      Thread.sleep(300)
      job.cancel()
      withTimeout(3_000) { job.join() }
      assertTrue(job.isCancelled)
      scope.cancel()
    }

  @Test
  fun cancelledCallerIsNotRetainedByWorkerOrOkHttpThreads() {
    server.enqueue(MockResponse().setBody("late").setHeadersDelay(30, TimeUnit.SECONDS))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Stands in for PlayerViewModel: only the coroutine lambda references it.
    var owner: Any? = Any()
    val ownerRef = WeakReference(owner)
    val job: Job = launchCapturing(scope, owner!!)
    owner = null
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    job.cancel()
    runBlocking { withTimeout(3_000) { job.join() } }
    var attempts = 0
    while (ownerRef.get() != null && attempts++ < 50) {
      System.gc()
      @Suppress("UNUSED_VARIABLE")
      val pressure = ByteArray(1 shl 20)
      Thread.sleep(20)
    }
    assertNull("cancelled translation still retains its owner (ViewModel leak)", ownerRef.get())
    scope.cancel()
  }

  private fun launchCapturing(
    scope: CoroutineScope,
    owner: Any,
  ): Job =
    scope.launch {
      val response = call().awaitText()
      // Uses the owner after the call, exactly like the ViewModel lambda's this$0.
      owner.hashCode() + response.code
    }
}
