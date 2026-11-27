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
import java.util.concurrent.atomic.AtomicBoolean
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
    // The reader streams the head of the window on its own socket so the first frame arrives
    // without waiting for a full chunk; prefetch workers fill the rest ahead of it.
    private const val HEAD_CHUNK_BYTES = 512L * 1024L
    private const val PARALLEL_CONNECTIONS = 4
    private const val PREFETCH_CHUNKS = 12
    private const val FETCH_ATTEMPTS = 3
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val IN_FLIGHT_WAIT_MS = 8_000L

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
        // ffmpeg otherwise reuses this keep-alive socket after NanoHTTPD times it out, and the
        // stale connection reports EOF at byte 0 ("Stream ends prematurely at 0").
        addHeader("Connection", "close")
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
      addHeader("Connection", "close")
    }
  }

  /**
   * Serves the requested byte window as an ordered stream while fetching its chunks with
   * [PARALLEL_CONNECTIONS] concurrent range requests kept [PREFETCH_CHUNKS] chunks ahead.
   */
  internal class ParallelRangeInputStream(
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

    private val cache = ConcurrentHashMap<Long, ByteArray>()
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()
    private val prefetchNext = AtomicLong(chunkOf(start) + 1L)
    private val closed = AtomicBoolean(false)

    @Volatile private var position = start
    private var currentData: ByteArray? = null
    private var offsetInChunk = (start - chunkStart(chunkOf(start))).toInt()
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null

    init {
      // Workers fill the out-of-order cache ahead of the reader. The reader never depends on
      // them: it streams its own bytes, so a slow or failing prefetch can cost throughput but
      // can no longer truncate playback.
      repeat(PARALLEL_CONNECTIONS) {
        Thread({ prefetch() }, "direct-link-prefetch").apply {
          isDaemon = true
          start()
        }
      }
    }

    private fun chunkOf(offset: Long): Long =
      if (offset < HEAD_CHUNK_BYTES) 0L else 1L + (offset - HEAD_CHUNK_BYTES) / CHUNK_BYTES

    private fun chunkStart(index: Long): Long =
      if (index == 0L) 0L else HEAD_CHUNK_BYTES + (index - 1L) * CHUNK_BYTES

    private fun chunkEnd(index: Long): Long =
      minOf(chunkStart(index) + if (index == 0L) HEAD_CHUNK_BYTES else CHUNK_BYTES, end + 1L) - 1L

    private fun prefetch() {
      while (!closed.get()) {
        try {
          val readerChunk = chunkOf(position)
          if (cache.size >= PREFETCH_CHUNKS) {
            Thread.sleep(40L)
            continue
          }
          cache.keys.removeAll { it < readerChunk }
          val index = prefetchNext.get()
          if (index > chunkOf(end)) return
          if (index <= readerChunk || cache.containsKey(index)) {
            prefetchNext.compareAndSet(index, index + 1L)
            continue
          }
          if (!prefetchNext.compareAndSet(index, index + 1L)) continue
          inFlight.add(index)
          try {
            val bytes = fetchChunk(index) ?: continue
            if (!closed.get() && chunkOf(position) <= index) cache[index] = bytes
          } finally {
            inFlight.remove(index)
          }
        } catch (interrupted: InterruptedException) {
          return
        }
      }
    }

    private fun fetchChunk(index: Long): ByteArray? {
      repeat(FETCH_ATTEMPTS) { attempt ->
        if (closed.get()) return null
        try {
          return fetchRange(url, chunkStart(index), chunkEnd(index))
        } catch (error: IOException) {
          if (attempt == FETCH_ATTEMPTS - 1) {
            Log.w(TAG, "Chunk $index fetch failed (${error.message})")
          } else {
            Thread.sleep(150L * (attempt + 1))
          }
        }
      }
      return null
    }

    /** Waits for a worker that is already downloading [index]; true once it is cached. */
    private fun awaitInFlight(index: Long): Boolean {
      val deadline = System.nanoTime() + IN_FLIGHT_WAIT_MS * 1_000_000L
      while (inFlight.contains(index) && !closed.get()) {
        if (cache.containsKey(index)) return true
        if (System.nanoTime() >= deadline) return false
        try {
          Thread.sleep(15L)
        } catch (_: InterruptedException) {
          return false
        }
      }
      return cache.containsKey(index)
    }

    /** Opens (or re-opens) the sequential socket at the current position; resumes after a drop. */
    private fun openOwnStream(): Boolean {
      closeOwnStream()
      repeat(FETCH_ATTEMPTS) { attempt ->
        if (closed.get()) return false
        try {
          val target = position
          val opened = URL(url).openConnection() as HttpURLConnection
          opened.requestMethod = "GET"
          opened.connectTimeout = CONNECT_TIMEOUT_MS
          opened.readTimeout = READ_TIMEOUT_MS
          headers.forEach { (name, value) -> opened.setRequestProperty(name, value) }
          opened.setRequestProperty("Range", "bytes=$target-$end")
          val code = opened.responseCode
          if (code != HttpURLConnection.HTTP_PARTIAL && !(code == HttpURLConnection.HTTP_OK && target == 0L)) {
            opened.disconnect()
            throw IOException("Unexpected range response $code")
          }
          connection = opened
          input = opened.inputStream
          return true
        } catch (error: IOException) {
          if (attempt == FETCH_ATTEMPTS - 1) {
            Log.w(TAG, "Stream resume at $position failed (${error.message})")
          } else {
            Thread.sleep(150L * (attempt + 1))
          }
        }
      }
      return false
    }

    private fun closeOwnStream() {
      runCatching { input?.close() }
      runCatching { connection?.disconnect() }
      input = null
      connection = null
    }

    override fun read(): Int {
      val single = ByteArray(1)
      val read = read(single, 0, 1)
      return if (read <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (len == 0) return 0
      var count = 0
      while (count < len && position <= end && !closed.get()) {
        val index = chunkOf(position)
        val cached = currentData ?: cache.remove(index)
        if (cached != null) {
          // The socket's offset no longer matches once bytes come from the cache.
          if (input != null) closeOwnStream()
          val within = (position - chunkStart(index)).toInt().coerceIn(0, cached.size)
          if (within >= cached.size) {
            currentData = null
            continue
          }
          currentData = cached
          offsetInChunk = within
          val toCopy = minOf(cached.size - within, len - count)
          System.arraycopy(cached, within, b, off + count, toCopy)
          offsetInChunk += toCopy
          position += toCopy
          count += toCopy
          if (offsetInChunk >= cached.size) currentData = null
          continue
        }
        if (input == null) {
          // A worker already downloading this chunk will hand it over shortly; opening a second
          // socket for the same bytes would split the link's bandwidth and stall both.
          if (count == 0 && awaitInFlight(index)) continue
          if (count > 0 && inFlight.contains(index)) break
          if (!openOwnStream()) break
        }
        // Stop at the chunk boundary so a prefetched chunk is served from the cache instead of
        // being downloaded a second time on the sequential socket.
        val untilChunkEnd = (chunkEnd(index) - position + 1L).toInt()
        val read = input!!.read(b, off + count, minOf(len - count, untilChunkEnd))
        if (read < 0) {
          closeOwnStream()
          if (!openOwnStream()) break
          continue
        }
        position += read
        count += read
        if (position > chunkEnd(index)) {
          val next = index + 1L
          // Keep the open socket when no worker owns the next chunk: a reconnect per chunk
          // costs a full TLS handshake and a fresh TCP ramp-up every 4 MiB.
          if (cache.containsKey(next) || inFlight.contains(next)) {
            closeOwnStream()
          } else {
            prefetchNext.updateAndGet { maxOf(it, next + 1L) }
          }
        }
      }
      return if (count == 0 && (position > end || closed.get())) -1 else count
    }

    override fun close() {
      closed.set(true)
      closeOwnStream()
      cache.clear()
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
