package app.infinity.mpvz.ui.player

import androidx.media3.common.Format
import androidx.media3.common.Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSubtitleParserFactoryTest {
  private val parser = object : SubtitleParser {
    override fun parse(
      data: ByteArray,
      offset: Int,
      length: Int,
      outputOptions: SubtitleParser.OutputOptions,
      output: Consumer<CuesWithTiming>,
    ) = Unit

    override fun getCueReplacementBehavior(): Int = CUE_REPLACEMENT_BEHAVIOR_REPLACE
  }

  @Test
  fun bitmapFormatsAreNotClaimedEvenWhenDelegateSupportsThem() {
    val delegate = RecordingFactory(supports = true, parser = parser)
    val factory = NativeSubtitleParserFactory(delegate)

    assertFalse(factory.supportsFormat(format(MimeTypes.APPLICATION_PGS)))
    assertFalse(factory.supportsFormat(format(MimeTypes.APPLICATION_DVBSUBS)))
    assertEquals(0, delegate.supportsCalls)
  }

  @Test
  fun nonBitmapFormatsDelegateUnchanged() {
    val delegate = RecordingFactory(supports = true, parser = parser)
    val factory = NativeSubtitleParserFactory(delegate)
    val ass = format(MimeTypes.TEXT_SSA)

    assertTrue(factory.supportsFormat(ass))
    assertSame(parser, factory.create(ass))
    assertEquals(CUE_REPLACEMENT_BEHAVIOR_REPLACE, factory.getCueReplacementBehavior(ass))
    assertEquals(1, delegate.supportsCalls)
    assertEquals(1, delegate.createCalls)
  }

  @Test
  fun delegateRejectionForOtherTextFormatsIsPreserved() {
    val delegate = RecordingFactory(supports = false, parser = parser)
    val factory = NativeSubtitleParserFactory(delegate)

    assertFalse(factory.supportsFormat(format(MimeTypes.APPLICATION_TTML)))
    assertEquals(1, delegate.supportsCalls)
  }

  @Test
  fun bitmapOperationsAreGuardedAsUnsupported() {
    val factory = NativeSubtitleParserFactory(RecordingFactory(supports = true, parser = parser))
    val pgs = format(MimeTypes.APPLICATION_PGS)

    assertThrows(IllegalStateException::class.java) { factory.create(pgs) }
    assertThrows(IllegalStateException::class.java) { factory.getCueReplacementBehavior(pgs) }
  }

  private fun format(mime: String): Format = Format.Builder()
    .setSampleMimeType(mime)
    .build()

  private class RecordingFactory(
    private val supports: Boolean,
    private val parser: SubtitleParser,
  ) : SubtitleParser.Factory {
    var supportsCalls = 0
    var createCalls = 0

    override fun supportsFormat(format: Format): Boolean {
      supportsCalls++
      return supports
    }

    override fun getCueReplacementBehavior(format: Format): Int =
      CUE_REPLACEMENT_BEHAVIOR_REPLACE

    override fun create(format: Format): SubtitleParser {
      createCalls++
      return parser
    }
  }
}
