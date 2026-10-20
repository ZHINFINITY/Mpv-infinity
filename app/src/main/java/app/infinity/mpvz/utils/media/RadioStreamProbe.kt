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
  private val client by lazy {
    OkHttpClient.Builder()
      .connectTimeout(3, TimeUnit.SECONDS)
      .readTimeout(3, TimeUnit.SECONDS)
      .callTimeout(4, TimeUnit.SECONDS)
      .build()
  }

  fun audioMimeType(contentType: String?): String? {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
    // M3U/HLS is a playlist, not proof of a direct audio stream.
    if (mime.contains("mpegurl") || mime.contains("scpls")) return null
    // Icecast uses application/ogg for Ogg-FLAC radio. Keep the container hint rather
    // than forcing raw audio/flac, and let MPV sniff the actual codec.
    if (mime == "application/ogg") return "audio/ogg"
    return mime.takeIf { it.startsWith("audio/") }
  }

  suspend fun detectAudioMimeType(url: String, headers: Map<String, String>): String? {
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
    return try {
      val request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
        header("Range", "bytes=0-0")
        if (headers.keys.none { it.equals("User-Agent", true) }) header("User-Agent", "MpvInfinity/2.0")
      }.build()
      client.newCall(request).awaitResponse().use { response ->
        if (response.isSuccessful) audioMimeType(response.header("Content-Type")) else null
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      null // Keep the existing web extractor fallback when the server cannot be identified.
    }
  }
}
