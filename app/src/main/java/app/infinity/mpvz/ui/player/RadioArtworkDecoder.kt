/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 */
package app.infinity.mpvz.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.security.MessageDigest

/** Decode only supplied stream pictures, downsampled and byte-cached across UI/notification. */
object RadioArtworkDecoder {
  private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
  }

  @Synchronized
  fun decode(encoded: String?): Bitmap? {
    if (encoded.isNullOrBlank() || encoded.length > 6 * 1024 * 1024) return null
    val key = MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
    cache.get(key)?.let { return it }
    val bytes = RadioPictureBlock.decode(encoded) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    cache.put(key, bitmap)
    return bitmap
  }
}
