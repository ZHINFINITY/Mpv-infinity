/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.cast

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates a receiver-compatible progressive MP4 containing the selected tracks.
 * The Cast default receiver cannot change embedded tracks in a local MKV/MP4
 * served as-is, but it can play a normal MP4 whose track set has been remuxed.
 */
internal object CastRemuxPipeline {
  data class Result(val file: File, val subtitleTrackIds: List<Long>)

  private val cache = ConcurrentHashMap<String, Result>()

  fun remux(
    context: Context,
    source: Uri,
    audioTrackIndex: Int?,
    subtitleTrackIndex: Int?,
  ): Result? {
    val key = "$source|$audioTrackIndex|$subtitleTrackIndex"
    cache[key]?.takeIf { it.file.exists() }?.let { return it }
    val input = openInput(context, source) ?: return null
    val extractor = MediaExtractor()
    val output = File(context.cacheDir, "cast-remux-${key.hashCode().toUInt().toString(16)}.mp4")
    return runCatching {
      input.use { stream ->
        FileOutputStream(File(context.cacheDir, "cast-input-${key.hashCode()}")).use { temp ->
          stream.copyTo(temp)
        }
      }
      val tempInput = File(context.cacheDir, "cast-input-${key.hashCode()}")
      extractor.setDataSource(tempInput.absolutePath)
      val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val selected = mutableListOf<Int>()
      var selectedSubtitle = -1
      for (index in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        when {
          mime.startsWith("video/") && selected.none { it == index } -> selected += index
          mime.startsWith("audio/") && index == (audioTrackIndex ?: firstTrack(extractor, "audio/")) -> selected += index
          mime.startsWith("text/") && index == subtitleTrackIndex -> {
            // Some MP4 text codecs are accepted by MediaMuxer; retain them when possible.
            selectedSubtitle = index
            selected += index
          }
        }
      }
      if (selected.none { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") || extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }) {
        muxer.release()
        tempInput.delete()
        error("No playable audio or video track")
      }
      val muxTrack = HashMap<Int, Int>()
      selected.forEach { index ->
        muxTrack[index] = muxer.addTrack(extractor.getTrackFormat(index))
      }
      muxer.start()
      val buffer = ByteBuffer.allocate(1024 * 1024)
      val info = android.media.MediaCodec.BufferInfo()
      selected.forEach { extractor.unselectTrack(it) }
      selected.forEach { extractor.selectTrack(it) }
      while (true) {
        val track = extractor.sampleTrackIndex
        if (track < 0) break
        val size = extractor.readSampleData(buffer, 0)
        if (size <= 0) {
          extractor.advance()
          continue
        }
        info.offset = 0
        info.size = size
        info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
        info.flags = extractor.sampleFlags
        muxer.writeSampleData(muxTrack.getValue(track), buffer, info)
        extractor.advance()
      }
      muxer.stop()
      muxer.release()
      tempInput.delete()
      Result(output, if (selectedSubtitle >= 0) listOf((selectedSubtitle + 1).toLong()) else emptyList()).also { cache[key] = it }
    }.getOrNull().also {
      if (it == null) {
      runCatching { extractor.release() }
      runCatching { output.delete() }
      }
    }
  }

  fun clear() {
    cache.values.forEach { runCatching { it.file.delete() } }
    cache.clear()
  }

  private fun firstTrack(extractor: MediaExtractor, prefix: String): Int? =
    (0 until extractor.trackCount).firstOrNull {
      extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)
    }

  private fun openInput(context: Context, uri: Uri) = when (uri.scheme?.lowercase()) {
    "file" -> uri.path?.let(::FileInputStream)
    "content" -> context.contentResolver.openInputStream(uri)
    else -> null
  }
}
