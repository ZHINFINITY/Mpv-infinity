package app.infinity.mpvz.ui.player

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Safely decodes a Vorbis/FLAC METADATA_BLOCK_PICTURE payload. */
object RadioPictureBlock {
  private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
  private const val MAX_ENCODED_CHARS = 6 * 1024 * 1024
  private const val FIXED_FIELDS_BYTES = 4 * 7

  /**
   * Returns embedded JPEG, PNG, or WebP bytes, or null for malformed/unsupported input.
   * The input is one base64-encoded METADATA_BLOCK_PICTURE block (not a FLAC stream).
   */
  @JvmStatic
  fun decode(encoded: String?): ByteArray? {
    if (encoded == null || encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) return null

    val block = try {
      Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
      return null
    }
    if (block.size < FIXED_FIELDS_BYTES) return null

    var offset = 0
    val pictureType = readInt(block, offset) ?: return null
    offset += 4
    // A front cover (type 3) is preferred by callers when several blocks are available;
    // this decoder accepts any embedded picture type rather than discarding useful art.
    @Suppress("UNUSED_VARIABLE") val ignoredPictureType = pictureType

    val mimeLength = readInt(block, offset) ?: return null
    offset += 4
    if (mimeLength < 0 || mimeLength > block.size - offset) return null
    val mime = String(block, offset, mimeLength, StandardCharsets.US_ASCII)
      .trim()
      .lowercase(Locale.ROOT)
    offset += mimeLength
    if (mime == "-->") return null // linked artwork, not embedded bytes
    if (mime != "image/jpeg" && mime != "image/png" && mime != "image/webp") return null

    val descriptionLength = readInt(block, offset) ?: return null
    offset += 4
    if (descriptionLength < 0 || descriptionLength > block.size - offset) return null
    offset += descriptionLength

    // width, height, color depth, and indexed-color count
    repeat(4) {
      if (readInt(block, offset) == null) return null
      offset += 4
    }

    val imageLength = readInt(block, offset) ?: return null
    offset += 4
    if (imageLength <= 0 || imageLength > MAX_IMAGE_BYTES || imageLength > block.size - offset) return null

    return block.copyOfRange(offset, offset + imageLength)
  }

  private fun readInt(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset > bytes.size - 4) return null
    return ((bytes[offset].toInt() and 0xff) shl 24) or
      ((bytes[offset + 1].toInt() and 0xff) shl 16) or
      ((bytes[offset + 2].toInt() and 0xff) shl 8) or
      (bytes[offset + 3].toInt() and 0xff)
  }
}
