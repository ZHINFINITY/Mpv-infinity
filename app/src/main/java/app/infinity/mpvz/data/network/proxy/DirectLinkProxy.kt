/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.data.network.proxy

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Loopback proxy for direct http(s) media links that splits the transfer into parallel HTTP
 * range chunks. A single TCP flow is throughput-capped by loss-based congestion control on
 * high-latency hosts; several concurrent range requests multiply the effective read rate and
 * hide per-request latency behind the prefetch pipeline.
 *
 * Servers that do not advertise `Accept-Ranges: bytes` are reported as ineligible so callers
 * fall back to the plain URL instead of losing seek support.
 */
class DirectLinkProxy private constructor() : NanoHTTPD("127.0.0.1", 0) {
  companion object {
    private const val TAG = "DirectLinkProxy"
    private const val TOKEN_BYTES = 24
    private const val CHUNK_BYTES = 4L * 1024L * 1024L
    private const val PARALLEL_CONNECTIONS = 4
    private const val PREFETCH_CHUNKS = 12
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    @Volatile
    private var instance: DirectLinkProxy? = null

    fun getInstance(): DirectLinkProxy =
      instance ?: synchronized(this) {
        instance ?: DirectLinkProxy().also {
          it.start(SOCKET_READ_TIMEOUT, true)
          instance = it
        }
      }

    fun stopInstance() {
      synchronized(this) {
        instance?.stop()
        instance = null
      }
    }

    data class Eligibility(val contentLength: Long)

    /** True for loopback URLs our own relays serve; those must never be re-proxied. */
    fun isLoopbackUrl(url: String): Boolean =
      url.startsWith("http://127.0.0.1:", true) ||
        url.startsWith("http://localhost:", true) ||
        url.startsWith("http://[::1]:", true) ||
        url.startsWith("http://[::ffff:127.0.0.1]:", true)

    /**
     * Probes the remote server. Returns null when the link cannot be proxied (no range support,
     * unreachable, non-media-friendly response), in which case the caller should use the plain URL.
     */
    fun probe(url: String, headers: Map<String, String> = emptyMap()): Eligibility? =
      runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        val acceptsRanges =
          connection.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
        val length = connection.contentLengthLong
        connection.disconnect()
        if (!acceptsRanges || length <= 0L) null else Eligibility(contentLength = length)
      }.getOrElse {
        Log.w(TAG, "Direct link probe failed (${it::class.java.simpleName})")
        null
      }
  }

  private class HeadResponse(
    status: Response.IStatus,
    mimeType: String,
    contentLength: Long,
  ) : Response(Response.Status.OK, mimeType, ByteArrayInputStream(ByteArray(0)), contentLength) {
    init {
      this.status = status
    }
  }

  private class RegisteredStream(
    val url: String,
    val mimeType: String,
    val fileSize: Long,
    val headers: Map<String, String>,
  )

  private val random = SecureRandom()
  private val streamsByToken = ConcurrentHashMap<String, RegisteredStream>()

  /** Registers a direct link and returns a credential-free loopback URL for mpv. */
  @Synchronized
  fun register(
    url: String,
    fileSize: Long,
    mimeType: String,
    headers: Map<String, String> = emptyMap(),
  ): String {
    val token = generateToken()
    streamsByToken[token] = RegisteredStream(url, mimeType, fileSize, headers)
    return URI("http", null, "127.0.0.1", listeningPort, "/$token", null, null).toASCIIString()
  }

  @Synchronized
  fun unregister(proxyUrl: String) {
    val token = URI(proxyUrl).path.removePrefix("/")
    streamsByToken.remove(token)
  }

  override fun serve(session: IHTTPSession): Response {
    val stream = streamsByToken[session.uri.removePrefix("/")]
      ?: return textResponse(Response.Status.NOT_FOUND, "Stream not found", session.method == Method.HEAD)
    if (session.method == Method.HEAD) {
      return HeadResponse(Response.Status.OK, stream.mimeType, stream.fileSize).apply {
        addHeader("Accept-Ranges", "bytes")
      }
    }
    if (session.method != Method.GET) {
      return textResponse(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed", false)
    }

    val range = parseRange(session.headers["range"], stream.fileSize)
      ?: return textResponse(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid range", false).apply {
        addHeader("Content-Range", "bytes */${stream.fileSize}")
      }
    val start = range.first
    val end = range.last
    return newFixedLengthResponse(
      Response.Status.PARTIAL_CONTENT,
      stream.mimeType,
      ParallelRangeInputStream(stream.url, stream.headers, start, end),
      end - start + 1L,
    ).apply {
      addHeader("Content-Range", "bytes $start-$end/${stream.fileSize}")
      addHeader("Accept-Ranges", "bytes")
    }
  }

  /**
   * Serves the requested byte window as an ordered stream while fetching its chunks with
   * [PARALLEL_CONNECTIONS] concurrent range requests kept [PREFETCH_CHUNKS] chunks ahead.
   */
  private class ParallelRangeInputStream(
    private val url: String,
    private val headers: Map<String, String>,
    private val start: Long,
    private val end: Long,
  ) : InputStream() {
    private fun fetchRange(url: String, start: Long, end: Long): ByteArray {
      val connection = URL(url).openConnection() as HttpURLConnection
      try {
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.setRequestProperty("Range", "bytes=$start-$end")
        if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
          throw IOException("Unexpected range response ${connection.responseCode}")
        }
        return connection.inputStream.use { input ->
          val expected = (end - start + 1L).toInt()
          val buffer = ByteArray(expected)
          var offset = 0
          while (offset < expected) {
            val read = input.read(buffer, offset, expected - offset)
            if (read < 0) throw IOException("Range truncated at $offset/$expected")
            offset += read
          }
          buffer
        }
      } finally {
        connection.disconnect()
      }
    }

    private val queue = LinkedBlockingQueue<Pair<Long, ByteArray>>(PREFETCH_CHUNKS)
    private val nextToFetch = AtomicLong(start / CHUNK_BYTES)
    private val lastChunk = end / CHUNK_BYTES
    private var currentData: ByteArray? = null
    private var currentChunk = -1L
    private var offsetInChunk = 0
    private var position = start
    @Volatile private var closed = false

    init {
      // Dedicated fetch workers give real parallel range requests: each claims the next chunk
      // index, downloads it out of order, and hands it to the in-order queue the reader drains.
      repeat(PARALLEL_CONNECTIONS) {
        Thread({
          while (!closed && !fetchNextIfAny()) { /* stop when stream is exhausted or closed */ }
        }, "direct-link-prefetch").apply {
          isDaemon = true
          start()
        }
      }
    }

    /** Returns true when the window is fully fetched (or the stream closed). */
    private fun fetchNextIfAny(): Boolean {
      if (closed) return true
      val chunk = nextToFetch.getAndUpdate { if (it < lastChunk) it + 1 else it }
      if (chunk > lastChunk) return true
      val chunkStart = chunk * CHUNK_BYTES
      val chunkEnd = minOf(chunkStart + CHUNK_BYTES - 1L, end)
      return try {
        val bytes = fetchRange(url, chunkStart, chunkEnd)
        if (queue.offer(chunk to bytes, 30L, TimeUnit.SECONDS)) {
          false
        } else {
          closed = true
          true
        }
      } catch (error: IOException) {
        closed = true
        true
      }
    }

    private fun advanceChunk(): Boolean {
      val entry = try {
        queue.poll(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        return false
      } ?: return false
      val (chunk, bytes) = entry
      if (chunk != currentChunk + 1 && position != chunk * CHUNK_BYTES && chunk != position / CHUNK_BYTES) {
        // Out-of-order delivery cannot be served without skipping; fail the stream.
        closed = true
        return false
      }
      currentChunk = chunk
      currentData = bytes
      offsetInChunk = (position - chunk * CHUNK_BYTES).toInt().coerceIn(0, bytes.size)
      return offsetInChunk < bytes.size
    }

    private fun fillBuffer(): Boolean {
      if (closed || position > end) return false
      while (currentData == null || offsetInChunk >= currentData!!.size) {
        if (!advanceChunk()) return false
      }
      return true
    }

    override fun read(): Int {
      if (!fillBuffer()) return -1
      val value = currentData!!.get(offsetInChunk).toInt() and 0xFF
      offsetInChunk++
      position++
      return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (len == 0) return 0
      if (!fillBuffer()) return -1
      var count = 0
      while (count < len && position <= end) {
        if (!fillBuffer()) break
        val toCopy = minOf(currentData!!.size - offsetInChunk, len - count, (end - position + 1L).toInt())
        System.arraycopy(currentData!!, offsetInChunk, b, off + count, toCopy)
        offsetInChunk += toCopy
        position += toCopy
        count += toCopy
      }
      return count
    }

    override fun close() {
      closed = true
      queue.clear()
    }
  }

  private fun parseRange(header: String?, fileSize: Long): LongRange? {
    if (header.isNullOrBlank()) return 0L until maxOf(fileSize, 1L)
    val match = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).find(header.trim()) ?: return null
    val (startText, endText) = match.destructured
    if (startText.isBlank()) return null
    val rangeStart = startText.toLong()
    val rangeEnd = endText.toLongOrNull() ?: (fileSize - 1L)
    if (rangeStart > rangeEnd || rangeStart >= fileSize) return null
    return rangeStart..minOf(rangeEnd, fileSize - 1L)
  }

  private fun generateToken(): String {
    var token: String
    do {
      val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    } while (streamsByToken.containsKey(token))
    return token
  }

  private fun textResponse(
    status: Response.IStatus,
    message: String,
    headOnly: Boolean,
  ): Response =
    if (headOnly) {
      HeadResponse(status, MIME_PLAINTEXT, message.toByteArray(Charsets.UTF_8).size.toLong())
    } else {
      newFixedLengthResponse(status, MIME_PLAINTEXT, message)
    }
}
