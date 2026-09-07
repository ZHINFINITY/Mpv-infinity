/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.ui.cast

import android.content.Context
import android.media.MediaCodec
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
 * Produces a Cast-compatible MP4 for a local source.
 *
 * Only audio and video elementary streams are copied. Embedded subtitle formats
 * are deliberately not passed to MediaMuxer because Android cannot reliably
 * mux arbitrary MKV text or bitmap subtitle codecs into MP4.
 */
internal object CastRemuxPipeline {
  data class Result(val file: File)

  private val cache = ConcurrentHashMap<String, Result>()

  fun remux(
    context: Context,
    source: Uri,
    audioTrackIndex: Int?,
  ): Result? {
    val key = "$source|$audioTrackIndex"
    cache[key]?.takeIf { it.file.isFile && it.file.length() > 0L }?.let { return it }

    val inputFile = File(context.cacheDir, "cast-input-${key.hashCode()}")
    val outputFile = File(context.cacheDir, "cast-remux-${key.hashCode()}.mp4")
    if (outputFile.exists()) outputFile.delete()

    return runCatching {
      copySourceToFile(context, source, inputFile)
      val extractor = MediaExtractor()
      var muxer: MediaMuxer? = null
      try {
        extractor.setDataSource(inputFile.absolutePath)
        val selectedTracks = selectTracks(extractor, audioTrackIndex)
        if (selectedTracks.isEmpty()) return@runCatching null

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxTrackByExtractorTrack = selectedTracks.associateWith { extractorTrack ->
          muxer.addTrack(extractor.getTrackFormat(extractorTrack))
        }
        muxer.start()
        selectedTracks.forEach(extractor::selectTrack)
        copySamples(extractor, muxer, muxTrackByExtractorTrack)
        muxer.stop()
        Result(outputFile).also { cache[key] = it }
      } finally {
        runCatching { muxer?.release() }
        runCatching { extractor.release() }
        inputFile.delete()
      }
    }.getOrNull().also {
      if (it == null) outputFile.delete()
    }
  }

  fun clear() {
    cache.values.forEach { result -> runCatching { result.file.delete() } }
    cache.clear()
  }

  private fun selectTracks(extractor: MediaExtractor, requestedAudioTrack: Int?): List<Int> {
    val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
      extractor.mimeAt(index).startsWith("video/")
    }
    val audioTracks = (0 until extractor.trackCount).filter { index ->
      extractor.mimeAt(index).startsWith("audio/")
    }
    val audioTrack = requestedAudioTrack
      ?.takeIf { it in audioTracks }
      ?: audioTracks.firstOrNull()
    return listOfNotNull(videoTrack, audioTrack).distinct()
  }

  private fun copySamples(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    muxTrackByExtractorTrack: Map<Int, Int>,
  ) {
    val buffer = ByteBuffer.allocate(BUFFER_SIZE)
    val info = MediaCodec.BufferInfo()
    while (true) {
      val extractorTrack = extractor.sampleTrackIndex
      if (extractorTrack < 0) return
      val muxTrack = muxTrackByExtractorTrack[extractorTrack]
      if (muxTrack != null) {
        buffer.clear()
        val sampleSize = extractor.readSampleData(buffer, 0)
        if (sampleSize > 0) {
          info.set(
            0,
            sampleSize,
            extractor.sampleTime.coerceAtLeast(0L),
            extractor.sampleFlags,
          )
          muxer.writeSampleData(muxTrack, buffer, info)
        }
      }
      extractor.advance()
    }
  }

  private fun copySourceToFile(context: Context, source: Uri, target: File) {
    val input = when (source.scheme?.lowercase()) {
      "file" -> FileInputStream(source.path ?: error("Missing file path"))
      "content" -> context.contentResolver.openInputStream(source)
        ?: error("Unable to open content source")
      else -> error("Unsupported local source scheme")
    }
    input.use { stream ->
      FileOutputStream(target).use { output -> stream.copyTo(output) }
    }
  }

  private fun MediaExtractor.mimeAt(index: Int): String =
    getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().lowercase()

  private const val BUFFER_SIZE = 1024 * 1024
}
