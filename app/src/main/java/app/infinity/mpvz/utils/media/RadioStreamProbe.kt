/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.utils.media

import app.infinity.mpvz.network.awaitResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

/** Icecast mounts often have no file extension. Inspect headers, never download the live body. */
object RadioStreamProbe {
  private const val CACHE_TTL_MS = 10 * 60 * 1000L
  data class Metadata(val icyName: String?, val icyLogo: String?)
  private data class CachedAudio(val mimeType: String, val metadata: Metadata, val checkedAt: Long)
  private data class RequestKey(val url: String, val headers: Map<String, String>)
  private val audioCache = object : LinkedHashMap<RequestKey, CachedAudio>(128, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, CachedAudio>?): Boolean = size > 128
  }

  private fun requestKey(url: String, headers: Map<String, String>): RequestKey {
    val identityHeaders = headers.mapKeys { it.key.lowercase() }.toMutableMap()
    identityHeaders.remove("referer")
    identityHeaders.remove("range")
    identityHeaders.putIfAbsent("user-agent", "MpvInfinity/2.0")
    return RequestKey(url, identityHeaders)
  }

  private fun cached(url: String, headers: Map<String, String>): CachedAudio? = synchronized(audioCache) {
    val key = requestKey(url, headers)
    val entry = audioCache[key] ?: return@synchronized null
    if (System.nanoTime() - entry.checkedAt > TimeUnit.MILLISECONDS.toNanos(CACHE_TTL_MS)) {
      audioCache.remove(key)
      null
    } else entry
  }

  fun cachedAudioMimeType(url: String, headers: Map<String, String>): String? = cached(url, headers)?.mimeType

  /** Genuine response headers from the bounded probe; never synthesized from a URL or title. */
  fun cachedMetadata(url: String, headers: Map<String, String>): Metadata? = cached(url, headers)?.metadata?.takeIf {
    it.icyName != null || it.icyLogo != null
  }

  private val client by lazy {
    OkHttpClient.Builder()
      .connectTimeout(3, TimeUnit.SECONDS)
      .readTimeout(3, TimeUnit.SECONDS)
      .callTimeout(4, TimeUnit.SECONDS)
      .build()
  }

  fun audioMimeType(contentType: String?): String? {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
    if (mime.contains("mpegurl") || mime.contains("scpls")) return null
    if (mime == "application/ogg") return "audio/ogg"
    return mime.takeIf { it.startsWith("audio/") }
  }

  suspend fun detectAudioMimeType(url: String, headers: Map<String, String>): String? {
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
    cachedAudioMimeType(url, headers)?.let { return it }
    return try {
      val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
        header("Range", "bytes=0-0")
        // Request station headers while probing. The real MPV request is also opted in below.
        header("Icy-MetaData", "1")
        if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", "MpvInfinity/2.0")
      }.build()
      client.newCall(request).awaitResponse().use { response ->
        val mimeType = if (response.isSuccessful) audioMimeType(response.header("Content-Type")) else null
        if (mimeType != null) synchronized(audioCache) {
          audioCache[requestKey(url, headers)] = CachedAudio(
            mimeType = mimeType,
            metadata = Metadata(
              icyName = response.header("icy-name")?.trim()?.takeIf { it.isNotEmpty() },
              icyLogo = response.header("icy-logo")?.trim()?.takeIf { it.isNotEmpty() },
            ),
            checkedAt = System.nanoTime(),
          )
        }
        mimeType
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      null
    }
  }
}
