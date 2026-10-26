package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackRecoveryPolicyTest {
  @Test
  fun recoversFromDecoderInitializationAndDecodeFailures() {
    assertTrue(
      NativePlaybackRecoveryPolicy.shouldFallbackToMpv(
        "ERROR_CODE_DECODING_FAILED",
        emptyList(),
      ),
    )
    assertTrue(
      NativePlaybackRecoveryPolicy.shouldFallbackToMpv(
        "ERROR_CODE_DECODER_INIT_FAILED",
        listOf("androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException"),
      ),
    )
  }

  @Test
  fun audioRendererFailureIsClassifiedAsAudioRatherThanVideo() {
    val kind = NativePlaybackRecoveryPolicy.fallbackKind(
      errorCodeName = "ERROR_CODE_DECODING_FAILED",
      causeClassNames = listOf("androidx.media3.decoder.ffmpeg.FfmpegDecoderException"),
      rendererName = "FfmpegAudioRenderer",
      rendererMimeType = "audio/vnd.dts.hd",
    )

    assertEquals(NativePlaybackFallbackKind.AUDIO_DECODER, kind)
  }

  @Test
  fun phoneReportedMain10HevcDecoderInitializationFailureFallsBackAsVideo() {
    assertEquals(
      NativePlaybackFallbackKind.VIDEO_DECODER,
      NativePlaybackRecoveryPolicy.fallbackKind(
        errorCodeName = "ERROR_CODE_DECODER_INIT_FAILED",
        causeClassNames = listOf(
          "androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException",
          "java.lang.IllegalArgumentException",
        ),
        rendererName = "MediaCodecVideoRenderer",
        rendererMimeType = "video/hevc",
      ),
    )
  }

  @Test
  fun videoRendererFailureIsClassifiedAsVideo() {
    assertEquals(
      NativePlaybackFallbackKind.VIDEO_DECODER,
      NativePlaybackRecoveryPolicy.fallbackKind(
        errorCodeName = "ERROR_CODE_DECODING_FAILED",
        causeClassNames = emptyList(),
        rendererName = "MediaCodecVideoRenderer",
        rendererMimeType = "video/hevc",
      ),
    )
  }

  @Test
  fun recoversFromUnsupportedContainers() {
    assertEquals(
      NativePlaybackFallbackKind.UNSUPPORTED_MEDIA,
      NativePlaybackRecoveryPolicy.fallbackKind(
        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
        emptyList(),
      ),
    )
  }

  @Test
  fun recoversWhenMedia3FlowCannotVerifyDecoderSdrOutput() {
    assertEquals(
      NativePlaybackFallbackKind.VIDEO_DECODER,
      NativePlaybackRecoveryPolicy.fallbackKind(
        "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED",
        listOf(
          "androidx.media3.exoplayer.video.VideoSink.VideoSinkException",
          "app.infinity.mpvz.ui.player.Media3FlowSdrOutputException",
        ),
      ),
    )
    assertFalse(
      NativePlaybackRecoveryPolicy.shouldFallbackToMpv(
        "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED",
        listOf("androidx.media3.exoplayer.video.VideoSink.VideoSinkException", "java.lang.IllegalStateException"),
      ),
    )
  }

  @Test
  fun doesNotTreatNetworkFailuresAsCodecFallbacks() {
    assertFalse(
      NativePlaybackRecoveryPolicy.shouldFallbackToMpv(
        "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
        listOf("java.io.IOException"),
      ),
    )
  }
}
