package app.infinity.mpvz.ui.player

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.subtitle.libass.LibassSubtitleRenderer
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Media3-integrated ASS extractor. It follows only_player's architecture: ASS samples and
 * Matroska font attachments are intercepted at extractor output, before Media3 turns them into
 * Cue objects. The normal Media3 output is retained so the track remains selectable/observable.
 */
@UnstableApi
internal class AssMatroskaExtractor(
  subtitleParserFactory: SubtitleParser.Factory,
  private val rendererProvider: () -> LibassSubtitleRenderer?,
) : MatroskaExtractor(subtitleParserFactory) {
  private var attachmentName: String? = null
  private var attachmentMime: String? = null
  private val subtitleSample = subtitleSampleField.get(this) as ParsableByteArray

  override fun getElementType(id: Int): Int = when (id) {
    ID_ATTACHMENTS, ID_ATTACHED_FILE -> ELEMENT_TYPE_MASTER
    ID_FILE_NAME, ID_FILE_MIME_TYPE -> ELEMENT_TYPE_STRING
    ID_FILE_DATA -> ELEMENT_TYPE_BINARY
    else -> super.getElementType(id)
  }

  override fun isLevel1Element(id: Int): Boolean = super.isLevel1Element(id) || id == ID_ATTACHMENTS

  @Throws(ParserException::class)
  override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
    when (id) {
      ID_EBML -> {
        wrapExtractorOutput()
        super.startMasterElement(id, contentPosition, contentSize)
      }
      ID_ATTACHED_FILE -> {
        attachmentName = null
        attachmentMime = null
      }
      else -> super.startMasterElement(id, contentPosition, contentSize)
    }
  }

  @Throws(ParserException::class)
  override fun endMasterElement(id: Int) {
    when (id) {
      ID_ATTACHED_FILE -> {
        attachmentName = null
        attachmentMime = null
      }
      else -> super.endMasterElement(id)
    }
  }

  override fun stringElement(id: Int, value: String) {
    when (id) {
      ID_FILE_NAME -> attachmentName = value
      ID_FILE_MIME_TYPE -> attachmentMime = value
      else -> super.stringElement(id, value)
    }
  }

  override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
    if (id != ID_FILE_DATA) {
      super.binaryElement(id, contentSize, input)
      return
    }
    val mime = attachmentMime.orEmpty().lowercase(Locale.ROOT)
    if (mime in FONT_MIME_TYPES) {
      val bytes = ByteArray(contentSize)
      input.readFully(bytes, 0, contentSize)
      rendererProvider()?.addFont(attachmentName ?: "attached-font", bytes)
    } else {
      input.skipFully(contentSize)
    }
  }

  private fun wrapExtractorOutput() {
    val output = extractorOutputField.get(this) as? ExtractorOutput ?: return
    if (output is AssExtractorOutput) return
    extractorOutputField.set(this, AssExtractorOutput(output, rendererProvider, this))
  }

  internal fun rawSubtitleSample(): ParsableByteArray = subtitleSample

  companion object {
    private const val ID_EBML = 0x1A45DFA3
    private const val ID_ATTACHMENTS = 0x1941A469
    private const val ID_ATTACHED_FILE = 0x61A7
    private const val ID_FILE_NAME = 0x466E
    private const val ID_FILE_MIME_TYPE = 0x4660
    private const val ID_FILE_DATA = 0x465C
    private const val ELEMENT_TYPE_MASTER = 1
    private const val ELEMENT_TYPE_STRING = 3
    private const val ELEMENT_TYPE_BINARY = 4
    private val FONT_MIME_TYPES = setOf(
      "font/ttf", "font/otf", "font/sfnt", "font/woff", "font/woff2",
      "application/font-sfnt", "application/font-woff", "application/x-truetype-font",
      "application/vnd.ms-opentype", "application/x-font-ttf",
    )
    private val extractorOutputField: Field = MatroskaExtractor::class.java
      .getDeclaredField("extractorOutput").apply { isAccessible = true }
    private val subtitleSampleField: Field = MatroskaExtractor::class.java
      .getDeclaredField("subtitleSample").apply { isAccessible = true }
  }
}

@UnstableApi
private class AssExtractorOutput(
  private val delegate: ExtractorOutput,
  private val rendererProvider: () -> LibassSubtitleRenderer?,
  private val extractor: AssMatroskaExtractor,
) : ExtractorOutput {
  override fun track(id: Int, type: Int): TrackOutput {
    val output = delegate.track(id, type)
    return if (type == C.TRACK_TYPE_TEXT) {
      AssTrackOutput(output, rendererProvider, extractor, id)
    } else output
  }
  override fun endTracks() = delegate.endTracks()
  override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private class AssTrackOutput(
  private val delegate: TrackOutput,
  private val rendererProvider: () -> LibassSubtitleRenderer?,
  private val extractor: AssMatroskaExtractor,
  private val extractorTrackId: Int,
) : TrackOutput {
  private var isAss = false
  private var trackId: String? = null

  override fun format(format: Format) {
    isAss = format.sampleMimeType?.let { it.contains("ass") || it.contains("ssa") } == true ||
      format.codecs?.lowercase(Locale.ROOT)?.let { it.contains("ass") || it.contains("ssa") } == true
    trackId = format.id ?: "embedded-ass:$extractorTrackId"
    if (isAss) {
      val codecPrivate = format.initializationData
        .asSequence()
        .filter { it.isNotEmpty() }
        .fold(ByteArray(0)) { result, part -> result + part }
      val document = completeAssDocument(codecPrivate)
      val added = rendererProvider()?.addTrack(trackId!!, document) == true
      android.util.Log.i(
        "Mpv∞-Media3",
        "ASS track document id=$trackId codecPrivate=${codecPrivate.size} document=${document.size} added=$added",
      )
    }
    delegate.format(format)
  }

  override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
    delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

  override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
    delegate.sampleData(data, length, sampleDataPart)

  override fun sampleMetadata(
    timeUs: Long,
    flags: Int,
    size: Int,
    offset: Int,
    cryptoData: TrackOutput.CryptoData?,
  ) {
    if (isAss && timeUs != C.TIME_UNSET) appendAssEvent(timeUs)
    delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
  }

  private fun appendAssEvent(timeUs: Long) {
    val id = trackId ?: return
    val source = extractor.rawSubtitleSample().data
    val limit = extractor.rawSubtitleSample().limit().coerceAtMost(source.size)
    if (limit <= 0) return
    val raw = String(source, 0, limit, StandardCharsets.UTF_8).replace("\u0000", "").trim()
    val event = canonicalEvent(raw) ?: return
    val times = assTimes(event) ?: return
    rendererProvider()?.appendEvent(id, event.toByteArray(StandardCharsets.UTF_8), timeUs, (times.second - times.first).coerceAtLeast(1L))
  }

  private fun canonicalEvent(raw: String): String? {
    val line = raw.lineSequence().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: return null
    if (line.startsWith("Dialogue:", true) || line.startsWith("Comment:", true)) return line
    val fields = line.split(',', limit = 12)
    if (fields.size < 4) return null
    val body = when {
      isTime(fields[1]) && isTime(fields[2]) -> fields.drop(0).joinToString(",")
      fields.size >= 5 && isTime(fields[2]) && isTime(fields[3]) -> fields.drop(1).joinToString(",")
      else -> return null
    }
    return "Dialogue: 0,$body"
  }

  private fun assTimes(event: String): Pair<Long, Long>? {
    val fields = event.substringAfter(':').trim().split(',', limit = 4)
    if (fields.size < 3) return null
    val start = parseTime(fields[1]) ?: return null
    val end = parseTime(fields[2]) ?: return null
    return start to end
  }

  private fun isTime(value: String): Boolean = parseTime(value) != null

  private fun parseTime(value: String): Long? {
    val p = value.trim().split(':')
    if (p.size != 3) return null
    val seconds = p[2].toDoubleOrNull() ?: return null
    val hours = p[0].toLongOrNull() ?: return null
    val minutes = p[1].toLongOrNull() ?: return null
    return ((hours * 3600 + minutes * 60) * 1_000_000L + (seconds * 1_000_000L).toLong())
  }

  companion object {
    private val DEFAULT_HEADER = """
      [Script Info]
      ScriptType: v4.00+
      PlayResX: 1920
      PlayResY: 1080
      [V4+ Styles]
      Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
      Style: Default,Arial,54,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,1,3,1,2,45,45,45,1
      [Events]
      Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    private fun completeAssDocument(codecPrivate: ByteArray): ByteArray {
      if (codecPrivate.isEmpty()) return DEFAULT_HEADER
      val text = String(codecPrivate, StandardCharsets.UTF_8)
        .replace("\u0000", "")
        .trim()
      if (text.contains("[Script Info]", ignoreCase = true)) {
        return text.toByteArray(StandardCharsets.UTF_8)
      }
      val suffix = if (text.isEmpty()) "" else "\n$text\n"
      return (String(DEFAULT_HEADER, StandardCharsets.UTF_8) + suffix)
        .toByteArray(StandardCharsets.UTF_8)
    }
  }
}
