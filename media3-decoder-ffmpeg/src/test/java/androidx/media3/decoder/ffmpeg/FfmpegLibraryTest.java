package androidx.media3.decoder.ffmpeg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.media3.common.MimeTypes;
import org.junit.Test;

public final class FfmpegLibraryTest {

  @Test
  public void dtsMimeTypesMapOnlyToDca() {
    assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS));
    assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS_EXPRESS));
    assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS_HD));
  }

  @Test
  public void nonDtsFormatsAreNotClaimedByTheExtension() {
    assertNull(FfmpegLibrary.getCodecName(MimeTypes.AUDIO_AAC));
    assertNull(FfmpegLibrary.getCodecName(MimeTypes.AUDIO_AC3));
    assertNull(FfmpegLibrary.getCodecName(MimeTypes.AUDIO_TRUEHD));
    assertNull(FfmpegLibrary.getCodecName(MimeTypes.VIDEO_H264));
    assertNull(FfmpegLibrary.getCodecName(MimeTypes.VIDEO_H265));
  }
}
