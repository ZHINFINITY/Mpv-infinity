package app.infinity.mpvz.ui.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.SubtitleParser

/**
 * Keeps bitmap subtitles as raw samples so Media3's legacy text renderer can decode them off the
 * playback thread. The ASS parser remains delegated to the existing factory.
 */
internal class NativeSubtitleParserFactory(
  private val delegate: SubtitleParser.Factory,
) : SubtitleParser.Factory {
  override fun supportsFormat(format: Format): Boolean {
    return !isBitmapSubtitle(format) && delegate.supportsFormat(format)
  }

  override fun getCueReplacementBehavior(format: Format): Int {
    check(!isBitmapSubtitle(format)) { "Bitmap subtitles must remain raw samples: $format" }
    return delegate.getCueReplacementBehavior(format)
  }

  override fun create(format: Format): SubtitleParser {
    check(!isBitmapSubtitle(format)) { "Bitmap subtitles must remain raw samples: $format" }
    return delegate.create(format)
  }

  companion object {
    internal fun isBitmapSubtitle(format: Format): Boolean {
      return format.sampleMimeType == MimeTypes.APPLICATION_PGS ||
        format.sampleMimeType == MimeTypes.APPLICATION_DVBSUBS
    }
  }
}
