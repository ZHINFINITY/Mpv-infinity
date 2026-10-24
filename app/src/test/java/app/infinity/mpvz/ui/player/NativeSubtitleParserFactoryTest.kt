package app.infinity.mpvz.ui.player

import androidx.media3.common.Format
import androidx.media3.common.Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSubtitleParserFactoryTest {
  private val assParser = parser("ass")
  private val defaultParser = parser("default")

  @Test
  fun bitmapFormatsAreNotClaimedEvenWhenFactoriesSupportThem() {
    val ass = RecordingFactory(supports = true, parser = assParser)
    val defaults = RecordingFactory(supports = true, parser = defaultParser)
    val factory = NativeSubtitleParserFactory(ass, defaults)

    assertFalse(factory.supportsFormat(format(MimeTypes.APPLICATION_PGS)))
    assertFalse(factory.supportsFormat(format(MimeTypes.APPLICATION_DVBSUBS)))
    assertEquals(0, ass.supportsCalls)
    assertEquals(0, defaults.supportsCalls)
  }

  @Test
  fun assFormatsStayOnAssFactory() {
    val ass = RecordingFactory(supportsMime = MimeTypes.TEXT_SSA, parser = assParser)
    val defaults = RecordingFactory(supports = false, parser = defaultParser)
    val factory = NativeSubtitleParserFactory(ass, defaults)
    val ssa = format(MimeTypes.TEXT_SSA)

    assertTrue(factory.supportsFormat(ssa))
    assertSame(assParser, factory.create(ssa))
    assertEquals(CUE_REPLACEMENT_BEHAVIOR_REPLACE, factory.getCueReplacementBehavior(ssa))
    assertEquals(0, defaults.createCalls)
  }

  @Test
  fun webvttSrtAndTtmlUseDefaultSubtitleParserFactory() {
    val ass = RecordingFactory(supports = false, parser = assParser)
    val factory = NativeSubtitleParserFactory(ass, DefaultSubtitleParserFactory())

    assertTrue(factory.supportsFormat(format(MimeTypes.TEXT_VTT)))
    assertTrue(factory.supportsFormat(format("text/vtt")))
    assertTrue(factory.supportsFormat(format(MimeTypes.APPLICATION_SUBRIP)))
    assertTrue(factory.supportsFormat(format("application/x-subrip")))
    assertTrue(factory.supportsFormat(format(MimeTypes.APPLICATION_TTML)))
    assertFalse(ass.createCalls > 0)
  }

  @Test
  fun webvttCreateGoesToDefaultNotAss() {
    val ass = RecordingFactory(supports = false, parser = assParser)
    val defaults = RecordingFactory(
      supportsMime = MimeTypes.TEXT_VTT,
      parser = defaultParser,
    )
    val factory = NativeSubtitleParserFactory(ass, defaults)
    val vtt = format(MimeTypes.TEXT_VTT)

    assertTrue(factory.supportsFormat(vtt))
    assertSame(defaultParser, factory.create(vtt))
    assertEquals(0, ass.createCalls)
    assertEquals(1, defaults.createCalls)
  }

  @Test
  fun bitmapOperationsAreGuardedAsUnsupported() {
    val factory = NativeSubtitleParserFactory(
      RecordingFactory(supports = true, parser = assParser),
      RecordingFactory(supports = true, parser = defaultParser),
    )
    val pgs = format(MimeTypes.APPLICATION_PGS)

    assertThrows(IllegalStateException::class.java) { factory.create(pgs) }
    assertThrows(IllegalStateException::class.java) { factory.getCueReplacementBehavior(pgs) }
  }

  private fun parser(id: String) = object : SubtitleParser {
    override fun parse(
      data: ByteArray,
      offset: Int,
      length: Int,
      outputOptions: SubtitleParser.OutputOptions,
      output: Consumer<CuesWithTiming>,
    ) = Unit

    override fun getCueReplacementBehavior(): Int = CUE_REPLACEMENT_BEHAVIOR_REPLACE

    override fun toString(): String = id
  }

  private fun format(mime: String): Format = Format.Builder()
    .setSampleMimeType(mime)
    .build()

  private class RecordingFactory(
    private val supports: Boolean = false,
    private val supportsMime: String? = null,
    private val parser: SubtitleParser,
  ) : SubtitleParser.Factory {
    var supportsCalls = 0
    var createCalls = 0

    override fun supportsFormat(format: Format): Boolean {
      supportsCalls++
      return when {
        supportsMime != null -> format.sampleMimeType == supportsMime
        else -> supports
      }
    }

    override fun getCueReplacementBehavior(format: Format): Int =
      CUE_REPLACEMENT_BEHAVIOR_REPLACE

    override fun create(format: Format): SubtitleParser {
      createCalls++
      return parser
    }
  }
}
