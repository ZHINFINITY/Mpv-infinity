package app.infinity.mpvz.ui.player

import java.util.Locale

/** Pure display mapping for values supplied by the native Media3 playback snapshot. */
internal object NativeStatsDisplay {
  const val UNAVAILABLE = "--"

  fun resolution(
    outputWidth: Int,
    outputHeight: Int,
    inputWidth: Int,
    inputHeight: Int,
  ): String {
    val (width, height) =
      if (outputWidth > 0 && outputHeight > 0) {
        outputWidth to outputHeight
      } else {
        inputWidth to inputHeight
      }
    return if (width > 0 && height > 0) "${width}×${height}" else UNAVAILABLE
  }

  fun outputSummary(
    resolution: String,
    dynamicRange: String?,
    colorSpace: String?,
    codec: String?,
    includeMissingParts: Boolean = false,
  ): String {
    val parts = listOf(resolution, dynamicRange, colorSpace, codec)
      .map { value -> value?.trim()?.takeIf { it.isNotEmpty() && it != UNAVAILABLE } }
    val displayParts =
      if (includeMissingParts) parts.map { it ?: UNAVAILABLE }
      else parts.filterNotNull().ifEmpty { listOf(UNAVAILABLE) }
    return displayParts.joinToString(" · ")
  }

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
