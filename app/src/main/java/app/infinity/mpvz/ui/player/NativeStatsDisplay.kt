package app.infinity.mpvz.ui.player

import java.util.Locale

/** Pure display mapping for values supplied by the native Media3 playback snapshot. */
internal object NativeStatsDisplay {
  const val UNAVAILABLE = "--"

  fun resolution(width: Int, height: Int): String =
    if (width > 0 && height > 0) "${width}×${height}" else UNAVAILABLE

  /** Returns the aspect ratio represented by the supplied Media3 dimensions and pixel ratio. */
  fun aspectRatio(width: Int, height: Int, pixelWidthHeightRatio: Float?): String {
    if (width <= 0 || height <= 0 || pixelWidthHeightRatio == null ||
      !pixelWidthHeightRatio.isFinite() || pixelWidthHeightRatio <= 0f
    ) {
      return UNAVAILABLE
    }
    val ratio = width.toDouble() * pixelWidthHeightRatio / height.toDouble()
    return ratio.takeIf { it.isFinite() && it > 0.0 }
      ?.let { String.format(Locale.ROOT, "%.3f:1", it) }
      ?: UNAVAILABLE
  }

  fun bitDepth(lumaBitDepth: Int?, chromaBitDepth: Int?): String {
    val luma = lumaBitDepth?.takeIf { it > 0 }
    val chroma = chromaBitDepth?.takeIf { it > 0 }
    return when {
      luma != null && chroma != null && luma == chroma -> "$luma-bit"
      luma != null && chroma != null -> "luma $luma-bit / chroma $chroma-bit"
      luma != null -> "$luma-bit luma"
      chroma != null -> "$chroma-bit chroma"
      else -> UNAVAILABLE
    }
  }

  fun isoCode(value: Int?): String = value?.takeIf { it > 0 }?.toString() ?: UNAVAILABLE

  fun hdrStaticMetadata(present: Boolean?): String = when (present) {
    true -> "present"
    false -> "not signalled"
    null -> UNAVAILABLE
  }

  fun frameCount(value: Long?): String = value?.takeIf { it >= 0L }?.toString() ?: UNAVAILABLE

  fun knownLabel(value: String?): String =
    value?.trim()?.takeIf(String::isNotEmpty) ?: UNAVAILABLE

  fun frameRate(framesPerSecond: Float): String =
    framesPerSecond.takeIf { it > 0f && it.isFinite() }
      ?.let { String.format(Locale.ROOT, "%.2f fps", it) }
      ?: UNAVAILABLE

  fun bitrate(bitsPerSecond: Long): String =
    bitsPerSecond.takeIf { it > 0L }
      ?.let { String.format(Locale.ROOT, "%.1f kbps", it / 1_000.0) }
      ?: UNAVAILABLE

  fun duration(milliseconds: Long): String =
    milliseconds.takeIf { it > 0L }?.let(::formatTime) ?: UNAVAILABLE

  fun position(milliseconds: Long): String = formatTime(milliseconds.coerceAtLeast(0L))

  fun frameOffset(offsetMicroseconds: Long, sampleCount: Long): String =
    if (sampleCount > 0L) {
      String.format(Locale.ROOT, "%.1f ms", offsetMicroseconds / 1_000.0)
    } else {
      UNAVAILABLE
    }

  fun audioChannels(channelCount: Int): String =
    channelCount.takeIf { it > 0 }?.let { "$it ch" } ?: UNAVAILABLE

  fun audioSampleRate(sampleRateHz: Int): String =
    sampleRateHz.takeIf { it > 0 }?.let { "$it Hz" } ?: UNAVAILABLE

  fun bufferedDuration(milliseconds: Long): String =
    String.format(Locale.ROOT, "%.1f s", milliseconds.coerceAtLeast(0L) / 1_000.0)

  fun bandwidth(bitsPerSecond: Long): String = bitrate(bitsPerSecond)

  fun byteSize(bytes: Long): String {
    if (bytes <= 0L) return UNAVAILABLE
    return when {
      bytes >= 1_073_741_824L -> String.format(Locale.ROOT, "%.2f GiB", bytes / 1_073_741_824.0)
      bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)
      bytes >= 1_024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1_024.0)
      else -> "$bytes B"
    }
  }

  private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
      String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, seconds)
    }
  }
}
