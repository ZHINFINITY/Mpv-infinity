/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.domain.thumbnail

/** Decode policy shared by disk and HTTP thumbnail image paths. */
internal object ThumbnailDecodePolicy {
  const val DefaultMaxEdge = ThumbnailCachePolicy.PreviewMaxEdge * 16
  // Artwork is not a video source: never reuse the multi-GiB remote-video input limit here.
  const val MaxEncodedImageBytes = 4L * 1024L * 1024L

  /** Largest power-of-two sample that still keeps the decoded edge near [targetMaxEdge]. */
  fun sampleSize(width: Int, height: Int, targetMaxEdge: Int): Int {
    if (width <= 0 || height <= 0) return 1
    val target = targetMaxEdge.coerceIn(1, DefaultMaxEdge)
    var sample = 1
    while (sample <= (1 shl 29) && maxOf(width, height).toLong() / (sample.toLong() * 2L) >= target) {
      sample = sample shl 1
    }
    return sample
  }

  fun targetMaxEdge(widthPx: Int, heightPx: Int, qualityMaxEdge: Int = DefaultMaxEdge): Int =
    maxOf(widthPx, heightPx)
      .takeIf { it > 0 }
      ?.coerceAtMost(qualityMaxEdge.coerceIn(1, DefaultMaxEdge))
      ?: qualityMaxEdge.coerceIn(1, DefaultMaxEdge)

  /** Unknown Content-Length responses still have a hard compressed-byte allocation bound. */
  fun readEncodedImage(stream: java.io.InputStream): ByteArray? {
    val output = java.io.ByteArrayOutputStream(64 * 1024)
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
      val count = stream.read(buffer)
      if (count < 0) break
      total += count
      if (total > MaxEncodedImageBytes) return null
      output.write(buffer, 0, count)
    }
    return output.toByteArray()
  }
}
