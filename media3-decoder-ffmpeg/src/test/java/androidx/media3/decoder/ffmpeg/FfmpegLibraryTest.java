package androidx.media3.decoder.ffmpeg;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.media3.common.MimeTypes;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class FfmpegLibraryTest {

  /** Every audio MIME type Media3 can route here, with the FFmpeg decoder that must handle it. */
  private static Map<String, String> expectedMapping() {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put(MimeTypes.AUDIO_AAC, "aac");
    expected.put(MimeTypes.AUDIO_MPEG, "mp3");
    expected.put(MimeTypes.AUDIO_MPEG_L1, "mp1");
    expected.put(MimeTypes.AUDIO_MPEG_L2, "mp2");
    expected.put(MimeTypes.AUDIO_AC3, "ac3");
    expected.put(MimeTypes.AUDIO_E_AC3, "eac3");
    expected.put(MimeTypes.AUDIO_TRUEHD, "truehd");
    expected.put(MimeTypes.AUDIO_DTS, "dca");
    expected.put(MimeTypes.AUDIO_DTS_EXPRESS, "dca");
    expected.put(MimeTypes.AUDIO_DTS_HD, "dca");
    expected.put(MimeTypes.AUDIO_VORBIS, "vorbis");
    expected.put(MimeTypes.AUDIO_OPUS, "opus");
    expected.put(MimeTypes.AUDIO_FLAC, "flac");
    expected.put(MimeTypes.AUDIO_ALAC, "alac");
    expected.put(MimeTypes.AUDIO_AMR_NB, "amrnb");
    expected.put(MimeTypes.AUDIO_AMR_WB, "amrwb");
    expected.put(MimeTypes.AUDIO_MLAW, "pcm_mulaw");
    expected.put(MimeTypes.AUDIO_ALAW, "pcm_alaw");
    expected.put(MimeTypes.AUDIO_MSGSM, "gsm_ms");
    return expected;
  }

  @Test
  public void everyRoutableAudioFormatMapsToItsFfmpegDecoder() {
    for (Map.Entry<String, String> entry : expectedMapping().entrySet()) {
      assertEquals(entry.getKey(), entry.getValue(), FfmpegLibrary.getCodecName(entry.getKey()));
    }
  }

  @Test
  public void dtsFamilyStillMapsToDca() {
    assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS));
    assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS_EXPRESS));
    assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS_HD));
  }

  @Test
  public void formatsWithoutAnFfmpegDecoderAreNotClaimed() {
    for (String mimeType :
        Arrays.asList(
            MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_MPEGH_MHA1,
            MimeTypes.AUDIO_MPEGH_MHM1,
            MimeTypes.AUDIO_DTS_X,
            MimeTypes.AUDIO_IAMF,
            MimeTypes.AUDIO_DSD,
            MimeTypes.AUDIO_RAW,
            MimeTypes.AUDIO_UNKNOWN,
            MimeTypes.AUDIO_MP4,
            MimeTypes.AUDIO_MATROSKA)) {
      assertNull(mimeType, FfmpegLibrary.getCodecName(mimeType));
    }
  }

  @Test
  public void videoAndTextAreNeverClaimed() {
    for (String mimeType :
        Arrays.asList(
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_AV1,
            MimeTypes.VIDEO_VP9,
            MimeTypes.TEXT_SSA,
            MimeTypes.APPLICATION_SUBRIP)) {
      assertNull(mimeType, FfmpegLibrary.getCodecName(mimeType));
    }
  }

  @Test
  public void missingInitializationDataDoesNotThrow() {
    for (String mimeType : expectedMapping().keySet()) {
      assertNull(mimeType, FfmpegAudioDecoder.getExtraData(mimeType, Collections.emptyList()));
    }
    assertNull(
        FfmpegAudioDecoder.getExtraData(
            MimeTypes.AUDIO_VORBIS, Collections.singletonList(new byte[] {1, 2, 3})));
  }

  @Test
  public void codecPrivateDataIsStillPassedThrough() {
    byte[] audioSpecificConfig = {0x12, 0x10};
    assertArrayEquals(
        audioSpecificConfig,
        FfmpegAudioDecoder.getExtraData(
            MimeTypes.AUDIO_AAC, Collections.singletonList(audioSpecificConfig)));
    byte[] opusHead = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};
    assertArrayEquals(
        opusHead,
        FfmpegAudioDecoder.getExtraData(MimeTypes.AUDIO_OPUS, Collections.singletonList(opusHead)));
    assertNull(
        FfmpegAudioDecoder.getExtraData(
            MimeTypes.AUDIO_TRUEHD, Collections.singletonList(new byte[] {1})));
  }
}
