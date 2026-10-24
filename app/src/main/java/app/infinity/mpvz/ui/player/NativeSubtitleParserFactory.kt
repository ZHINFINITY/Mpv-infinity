package app.infinity.mpvz.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Composite Native subtitle parser. ASS/SSA stays on libass. WebVTT, SRT, TTML and other
 * DefaultSubtitleParserFactory formats use Media3. Bitmap PGS/DVB remain raw samples.
 */
internal class NativeSubtitleParserFactory(
  private val assFactory: SubtitleParser.Factory,
  private val defaultFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {
  override fun supportsFormat(format: Format): Boolean {
    if (isBitmapSubtitle(format)) return false
    return assFactory.supportsFormat(format) || defaultFactory.supportsFormat(format)
  }

  override fun getCueReplacementBehavior(format: Format): Int {
    check(!isBitmapSubtitle(format)) { "Bitmap subtitles must remain raw samples: $format" }
    return factoryFor(format).getCueReplacementBehavior(format)
  }

  override fun create(format: Format): SubtitleParser {
    check(!isBitmapSubtitle(format)) { "Bitmap subtitles must remain raw samples: $format" }
    return factoryFor(format).create(format)
  }

  private fun factoryFor(format: Format): SubtitleParser.Factory {
    return if (assFactory.supportsFormat(format)) assFactory else defaultFactory
  }

  companion object {
    internal fun isBitmapSubtitle(format: Format): Boolean {
      return format.sampleMimeType == MimeTypes.APPLICATION_PGS ||
        format.sampleMimeType == MimeTypes.APPLICATION_DVBSUBS
    }
  }
}
