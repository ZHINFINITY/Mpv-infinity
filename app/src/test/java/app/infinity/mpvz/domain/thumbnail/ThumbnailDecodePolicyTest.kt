package app.infinity.mpvz.domain.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThumbnailDecodePolicyTest {
  @Test
  fun sampleSizeUsesPowerOfTwoAndAvoidsFullSizeDecodeForLargeImages() {
    assertEquals(1, ThumbnailDecodePolicy.sampleSize(640, 360, 1024))
    assertEquals(2, ThumbnailDecodePolicy.sampleSize(4000, 2000, 1024))
    assertEquals(8, ThumbnailDecodePolicy.sampleSize(8192, 4096, 1024))
  }

  @Test
  fun malformedBoundsAndTargetsRemainSafe() {
    assertEquals(1, ThumbnailDecodePolicy.sampleSize(0, 4000, 128))
    assertEquals(1, ThumbnailDecodePolicy.sampleSize(4000, 0, 128))
    assertEquals(ThumbnailDecodePolicy.DefaultMaxEdge, ThumbnailDecodePolicy.targetMaxEdge(0, 0))
    assertEquals(128, ThumbnailDecodePolicy.targetMaxEdge(128, 72))
    assertTrue(ThumbnailDecodePolicy.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 1) > 0)
  }

  @Test
  fun targetIsClampedToBoundedDecodeEdge() {
    assertEquals(ThumbnailCachePolicy.PreviewMaxEdge * 16, ThumbnailDecodePolicy.targetMaxEdge(100_000, 100_000))
  }

  @Test
  fun userQualityLimitsAndVisibleTargetBothConstrainDecodeSize() {
    assertEquals(720, ThumbnailDecodePolicy.targetMaxEdge(1920, 1080, 720))
    assertEquals(1440, ThumbnailDecodePolicy.targetMaxEdge(1920, 1080, 1440))
    assertEquals(128, ThumbnailDecodePolicy.targetMaxEdge(128, 72, 1440))
  }

  @Test
  fun artworkInputHasAnIndependentFourMiBCompressedLimit() {
    assertEquals(4L * 1024L * 1024L, ThumbnailDecodePolicy.MaxEncodedImageBytes)
    val small = byteArrayOf(1, 2, 3)
    org.junit.Assert.assertArrayEquals(small, ThumbnailDecodePolicy.readEncodedImage(small.inputStream()))
    val oversized = ByteArray(ThumbnailDecodePolicy.MaxEncodedImageBytes.toInt() + 1)
    assertEquals(null, ThumbnailDecodePolicy.readEncodedImage(oversized.inputStream()))
  }

  @Test
  fun localArtworkSidecarsAreSampledFromFilesWithoutTheStreamByteCap() {
    val relative = "src/main/java/app/infinity/mpvz/domain/thumbnail/EmbeddedArtworkCandidates.kt"
    val source = listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    val fileDecoder = source.substringAfter("private fun decodeFileSampled(").substringBefore("private fun decodeStreamSampled(")
    assertTrue(fileDecoder.contains("BitmapFactory.decodeFile(path, bounds)"))
    assertTrue(fileDecoder.contains("ThumbnailDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight, ArtworkMaxEdge)"))
    assertFalse(fileDecoder.contains("ThumbnailDecodePolicy.readEncodedImage"))
  }
}
