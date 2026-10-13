package app.infinity.mpvz.ui.player

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
  fun recoversFromUnsupportedContainers() {
    assertTrue(
      NativePlaybackRecoveryPolicy.shouldFallbackToMpv(
        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
        emptyList(),
      ),
    )
  }

  @Test
  fun recoversWhenMedia3FlowCannotVerifyDecoderSdrOutput() {
    assertTrue(
      NativePlaybackRecoveryPolicy.shouldFallbackToMpv(
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
