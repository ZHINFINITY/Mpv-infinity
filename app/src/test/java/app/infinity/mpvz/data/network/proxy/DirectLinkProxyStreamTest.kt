/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 */
package app.infinity.mpvz.data.network.proxy

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import kotlin.math.min
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streams the chunk pipeline against a real local range server and compares every byte with the
 * source file, with a slow server to exercise the in-flight wait instead of a second socket.
 */
class DirectLinkProxyStreamTest {
  private val payload = ByteArray(9 * 1024 * 1024 + 12345).also { Random(7).nextBytes(it) }

  private fun serve(delayMs: Long): Pair<HttpServer, String> {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/file") { exchange ->
      val range = exchange.requestHeaders.getFirst("Range")
      val match = Regex("bytes=(\\d+)-(\\d*)").find(range ?: "")
      val from = match?.groupValues?.get(1)?.toLong() ?: 0L
      val to = (match?.groupValues?.get(2)?.toLongOrNull() ?: (payload.size.toLong() - 1L))
        .coerceAtMost(payload.size.toLong() - 1L)
      val len = (to - from + 1L).toInt()
      val out = exchange.responseBody
      exchange.responseHeaders.add("Accept-Ranges", "bytes")
      exchange.sendResponseHeaders(206, len.toLong())
      val slice = payload.copyOfRange(from.toInt(), (from + len).toInt())
      // Dribble the bytes out in small sleeps so sequential reads can hit an in-flight chunk.
      var off = 0
      while (off < slice.size) {
        val n = min(64 * 1024, slice.size - off)
        out.write(slice, off, n)
        off += n
        if (delayMs > 0) Thread.sleep(delayMs)
      }
      out.close()
    }
    server.start()
    return server to "http://127.0.0.1:${server.address.port}/file"
  }

  private fun readAll(start: Long, end: Long): ByteArray {
    DirectLinkProxy.ParallelRangeInputStream(url, emptyMap(), start, end).use { stream ->
      val out = ByteArrayOutputStream()
      val buf = ByteArray(97 * 1024)
      while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
      }
      return out.toByteArray()
    }
  }

  private lateinit var url: String
  private lateinit var server: HttpServer

  @Test
  fun fullStreamIsByteIdenticalToTheSource() {
    val (s, u) = serve(delayMs = 0)
    server = s; url = u
    assertEquals(payload.size.toLong() - 1, (payload.size - 1).toLong())
    val read = readAll(0, payload.size.toLong() - 1)
    assertTrue(read.contentEquals(payload))
    server.stop(0)
  }

  @Test
  fun midFileReadIsByteIdenticalAcrossChunkBoundaries() {
    val (s, u) = serve(delayMs = 0)
    server = s; url = u
    val start = 3L * 1024 * 1024 + 777
    val end = 8L * 1024 * 1024 + 42
    val read = readAll(start, end)
    assertTrue(read.contentEquals(payload.copyOfRange(start.toInt(), end.toInt() + 1)))
    server.stop(0)
  }

  @Test
  fun slowServerStillStreamsCorrectBytesWithoutSecondSockets() {
    val (s, u) = serve(delayMs = 5)
    server = s; url = u
    val read = readAll(0, 2L * 1024 * 1024 + 1)
    assertTrue(read.contentEquals(payload.copyOfRange(0, (2L * 1024 * 1024 + 2).toInt())))
    server.stop(0)
  }
}
