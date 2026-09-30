package app.infinity.mpvz.utils.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoResolutionFormatterTest {
  @Test
  fun portraitAndLandscapeDimensionsUseTheSameConventionalResolutionTier() {
    assertEquals("720p", VideoResolutionFormatter.format(1280, 720))
    assertEquals("720p", VideoResolutionFormatter.format(720, 1280))
    assertEquals("1080p", VideoResolutionFormatter.format(1920, 1080))
    assertEquals("1080p", VideoResolutionFormatter.format(1080, 1920))
    assertEquals("1440p", VideoResolutionFormatter.format(2560, 1440))
    assertEquals("1440p", VideoResolutionFormatter.format(1440, 2560))
  }

  @Test
  fun croppedUhdAndDci4kDoNotGetMisclassifiedAs1440pOr8k() {
    assertEquals("2160p", VideoResolutionFormatter.format(3840, 1600))
    assertEquals("2160p", VideoResolutionFormatter.format(1600, 3840))
    assertEquals("2160p", VideoResolutionFormatter.format(4096, 2160))
    assertEquals("4320p", VideoResolutionFormatter.format(7680, 4320))
  }

  @Test
  fun invalidOrUnavailableDimensionsRemainUnknown() {
    assertEquals("--", VideoResolutionFormatter.format(0, 1080))
    assertEquals("--", VideoResolutionFormatter.format(1920, 0))
    assertEquals("--", VideoResolutionFormatter.format(-1, 1080))
  }
}
