package app.infinity.mpvz.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedMotionArtworkTest {
  @Test
  fun encodedMotionArtHasAHardByteBound() {
    assertEquals(8 * 1024 * 1024, EmbeddedMotionArtwork.MaxEncodedBytes)
    assertNull(EmbeddedMotionArtwork.animatedBytes(ByteArray(EmbeddedMotionArtwork.MaxEncodedBytes + 1)))
    assertFalse(EmbeddedMotionArtwork.hasSupportedAnimationSignature(ByteArray(EmbeddedMotionArtwork.MaxEncodedBytes + 1)))
  }

  @Test
  fun unsupportedOrMalformedSignaturesAreRejected() {
    assertNull(EmbeddedMotionArtwork.animatedBytes(ByteArray(20)))
    assertNull(EmbeddedMotionArtwork.animatedBytes(ByteArray(21).apply { "GIF89x".encodeToByteArray().copyInto(this) }))
    assertNull(EmbeddedMotionArtwork.animatedBytes("RIFF0000WEBPVP8X".encodeToByteArray()))
    assertFalse(EmbeddedMotionArtwork.hasSupportedAnimationSignature(ByteArray(20)))
  }

  @Test
  fun embeddedGifSignatureIsAcceptedWithinTheEncodedByteBound() {
    val bytes = ByteArray(21).apply { "GIF89a".encodeToByteArray().copyInto(this) }
    assertTrue(EmbeddedMotionArtwork.hasSupportedAnimationSignature(bytes))
  }

  @Test
  fun onlyAnimatedVp8xWebpIsAccepted() {
    val animatedWebp = ByteArray(21).apply {
      "RIFF".encodeToByteArray().copyInto(this, 0)
      "WEBP".encodeToByteArray().copyInto(this, 8)
      "VP8X".encodeToByteArray().copyInto(this, 12)
      this[20] = 0x02
    }
    assertTrue(EmbeddedMotionArtwork.hasSupportedAnimationSignature(animatedWebp))
    animatedWebp[20] = 0
    assertFalse(EmbeddedMotionArtwork.hasSupportedAnimationSignature(animatedWebp))
  }
}
