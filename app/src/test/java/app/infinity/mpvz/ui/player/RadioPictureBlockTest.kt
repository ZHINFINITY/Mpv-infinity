package app.infinity.mpvz.ui.player

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioPictureBlockTest {
  @Test
  fun decodesEmbeddedPng() {
    val image = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    assertArrayEquals(image, RadioPictureBlock.decode(block(mime = "image/png", image = image)))
  }

  @Test
  fun rejectsTruncatedAndMalformedLengths() {
    val valid = Base64.getDecoder().decode(block(mime = "image/png", image = byteArrayOf(1, 2, 3)))

    assertNull(RadioPictureBlock.decode(Base64.getEncoder().encodeToString(valid.copyOf(valid.size - 1))))

    val hugeLength = valid.copyOf()
    val lengthOffset = hugeLength.size - 7
    putInt(hugeLength, lengthOffset, Int.MAX_VALUE)
    assertNull(RadioPictureBlock.decode(Base64.getEncoder().encodeToString(hugeLength)))
  }

  @Test
  fun rejectsInvalidBase64() {
    assertNull(RadioPictureBlock.decode("not base64 !!!"))
  }

  @Test
  fun rejectsUrlLinkedPictureMime() {
    assertNull(RadioPictureBlock.decode(block(mime = "-->", image = byteArrayOf(1, 2, 3))))
  }

  private fun block(mime: String, image: ByteArray): String {
    val output = ByteArrayOutputStream()
    fun putInt(value: Int) {
      output.write((value ushr 24) and 0xff)
      output.write((value ushr 16) and 0xff)
      output.write((value ushr 8) and 0xff)
      output.write(value and 0xff)
    }
    val mimeBytes = mime.toByteArray(StandardCharsets.US_ASCII)
    putInt(3) // front cover
    putInt(mimeBytes.size)
    output.write(mimeBytes)
    putInt(0) // description length
    repeat(4) { putInt(0) } // width, height, depth, indexed colors
    putInt(image.size)
    output.write(image)
    return Base64.getEncoder().encodeToString(output.toByteArray())
  }

  private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
  }
}
