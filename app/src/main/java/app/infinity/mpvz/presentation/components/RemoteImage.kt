/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.infinity.mpvz.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.infinity.mpvz.domain.thumbnail.EmbeddedArtworkResolver
import app.infinity.mpvz.network.awaitResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Composable
fun RemoteImage(
  url: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  alpha: Float = 1f,
) {
  val context = LocalContext.current
  val client = koinInject<OkHttpClient>()
  var bitmap by remember(url) { mutableStateOf(RemoteImageLoader.getFromMemory(url)) }

  LaunchedEffect(url) {
    if (bitmap == null) {
      bitmap = RemoteImageLoader.load(context, client, url)
    }
  }

  val imageBitmap = remember(bitmap) { bitmap?.asImageBitmap() }
  if (imageBitmap != null) {
    Image(
      bitmap = imageBitmap,
      contentDescription = contentDescription,
      modifier = modifier,
      contentScale = contentScale,
      alpha = alpha,
    )
  }
}

internal object RemoteImageLoader {
  private const val MAX_IMAGE_DIMENSION = 1024
  private const val CACHE_DIRECTORY = "remote_images"
  private const val FAILURE_RETRY_MS = 30_000L
  private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
  private val failedAt = ConcurrentHashMap<String, Long>()
  private val memoryCache =
    object : LruCache<String, Bitmap>(
      ((Runtime.getRuntime().maxMemory() / 1024L) / 32L).toInt(),
    ) {
      override fun sizeOf(
        key: String,
        value: Bitmap,
      ): Int = value.byteCount / 1024
    }

  fun getFromMemory(url: String): Bitmap? = synchronized(memoryCache) { memoryCache.get(url) }

  fun putInMemory(url: String, bitmap: Bitmap) {
    synchronized(memoryCache) { memoryCache.put(url, bitmap) }
  }

  suspend fun load(
    context: Context,
    client: OkHttpClient,
    url: String,
  ): Bitmap? {
    if (url.isBlank()) return null
    getFromMemory(url)?.let { return it }
    val failedAtMs = failedAt[url]
    if (failedAtMs != null) {
      if (SystemClock.elapsedRealtime() - failedAtMs < FAILURE_RETRY_MS) return null
      failedAt.remove(url, failedAtMs)
    }

    val candidate =
      loaderScope.async(start = CoroutineStart.LAZY) {
        loadUncoalesced(context, client, url).also { result ->
          if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            if (result == null) failedAt[url] = SystemClock.elapsedRealtime() else failedAt.remove(url)
          }
        }
      }
    val operation =
      inFlight.putIfAbsent(url, candidate)?.also {
        candidate.cancel()
      } ?: candidate.also { owned ->
        owned.invokeOnCompletion { inFlight.remove(url, owned) }
        owned.start()
      }
    return operation.await()
  }

  private suspend fun loadUncoalesced(
    context: Context,
    client: OkHttpClient,
    url: String,
  ): Bitmap? {
    getFromMemory(url)?.let { return it }

    val parsedUri = runCatching { Uri.parse(url) }.getOrNull()
    val scheme = parsedUri?.scheme?.lowercase()
    val localBitmap =
      when (scheme) {
        "content", "android.resource" -> decodeSampled(context, parsedUri)
          ?: EmbeddedArtworkResolver.decodeArtworkUri(context, url)
        "file" -> parsedUri.path?.let(::File)?.let(::decodeSampled)
          ?: EmbeddedArtworkResolver.decodeArtworkUri(context, url)
        null, "" -> File(url).takeIf { it.isFile }?.let(::decodeSampled)
          ?: EmbeddedArtworkResolver.decodeArtworkUri(context, url)
        else -> null
      }
    if (localBitmap != null) {
      synchronized(memoryCache) { memoryCache.put(url, localBitmap) }
      return localBitmap
    }

    if (scheme != "http" && scheme != "https") {
      return null
    }

    val httpUrl = url.toHttpUrlOrNull() ?: return null

    val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    val cacheFile = File(cacheDirectory, hash(url))
    decodeSampled(cacheFile)?.let { bitmap ->
      synchronized(memoryCache) { memoryCache.put(url, bitmap) }
      return bitmap
    }

    val host = httpUrl.host
    val request =
      runCatching {
        Request
          .Builder()
          .url(httpUrl)
          .header("User-Agent", "Mozilla/5.0 (Android) MpvInfinity")
          .apply { if (host.isNotBlank()) header("Referer", "https://$host") }
          .build()
      }.getOrNull() ?: return null

    return runCatching {
      client.newCall(request).awaitResponse().use { response ->
        if (!response.isSuccessful) return@use null
        val bytes = response.body.bytes()
        FileOutputStream(cacheFile).use { it.write(bytes) }
        decodeSampled(cacheFile)?.also { bitmap ->
          synchronized(memoryCache) { memoryCache.put(url, bitmap) }
        }
      }
    }.getOrNull()
  }

  private fun decodeSampled(
    context: Context,
    uri: Uri,
  ): Bitmap? =
    runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
      }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

      var sampleSize = 1
      while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_IMAGE_DIMENSION) {
        sampleSize *= 2
      }
      context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(
          input,
          null,
          BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
          },
        )
      }
    }.getOrNull()

  private fun decodeSampled(file: File): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_IMAGE_DIMENSION) {
      sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
      file.absolutePath,
      BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
      },
    )
  }

  private fun hash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray())
      .joinToString("") { byte -> "%02x".format(byte) }
}
