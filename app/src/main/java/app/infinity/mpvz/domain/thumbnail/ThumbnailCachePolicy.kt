/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.domain.thumbnail

import kotlin.math.roundToInt

/** Both bitmap tiers share one hard budget; scrolling never clears either tier. */
internal object ThumbnailCachePolicy {
  const val MinBytes = 8L * 1024L * 1024L
  const val MaxBytes = 64L * 1024L * 1024L
  const val PreviewMaxEdge = 128
  const val MaxConcurrentRequests = 8

  fun maxBytes(memoryClassBytes: Long, lowRam: Boolean): Long =
    (memoryClassBytes / if (lowRam) 12L else 8L).coerceIn(MinBytes, MaxBytes)

  fun previewBytes(totalBytes: Long): Long = totalBytes.coerceIn(MinBytes, MaxBytes) / 4L
  fun primaryBytes(totalBytes: Long): Long = totalBytes.coerceIn(MinBytes, MaxBytes) - previewBytes(totalBytes)

  fun sourceIdentity(path: String, uri: String, size: Long, dateModified: Long, duration: Long): String =
    "${path.ifBlank { uri }}|$size|$dateModified|$duration"

  fun previewSize(width: Int, height: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val scale = minOf(1.0, PreviewMaxEdge.toDouble() / maxOf(width, height))
    return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
  }

  fun trimBytes(currentBytes: Long, runningCritical: Boolean): Long =
    if (runningCritical) 0L else currentBytes.coerceAtLeast(0L) / 2L
}
