/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.utils.media

import java.io.File

/**
 * Reads the real family name from a font file's `name` table (platform id 3 preferred,
 * name id 1). libass matches `Fontname` against the family name, not the file name, so
 * registering a TTF under "Anton-Regular" while the settings say "Anton" never resolves.
 */
object FontFamilyName {
  fun read(file: File): String? = runCatching {
    file.inputStream().use { input ->
      val header = ByteArray(12)
      if (input.read(header) != 12) return null
      if (header[0] != 0x00.toByte() || header[1] != 0x01.toByte()) return null // sfnt version
      val numTables = readU16(header, 4)
      val tableDir = ByteArray(numTables * 16)
      if (input.read(tableDir) != tableDir.size) return null
      var nameOffset = -1L
      for (t in 0 until numTables) {
        val base = t * 16
        val tag = String(tableDir, base, 4, Charsets.US_ASCII)
        if (tag == "name") {
          nameOffset = readU32(tableDir, base + 8)
          break
        }
      }
      if (nameOffset < 0L) return null

      val name = ByteArray(file.length().toInt())
      file.inputStream().use { full -> full.readFully(name) }
      val count = readU16(name, 2)
      val stringOffset = readU16(name, 4)
      var asciiFamily: String? = null
      var p = 6
      for (r in 0 until count) {
        val platformId = readU16(name, p)
        val nameId = readU16(name, p + 6)
        val length = readU16(name, p + 8)
        val offset = readU16(name, p + 10)
        if (nameId == 1) {
          val raw = name.copyOfRange(stringOffset + offset, stringOffset + offset + length)
          val decoded =
            if (platformId == 3 || platformId == 0) String(raw, Charsets.UTF_16BE)
            else String(raw, Charsets.US_ASCII)
          if (platformId == 3) return decoded.trim().ifEmpty { null }
          asciiFamily = decoded.trim().ifEmpty { null }
        }
        p += 12
      }
      asciiFamily
    }
  }.getOrNull()

  private fun readU16(bytes: ByteArray, off: Int): Int =
    ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

  private fun readU32(bytes: ByteArray, off: Int): Long =
    ((bytes[off].toLong() and 0xFF) shl 24) or
      ((bytes[off + 1].toLong() and 0xFF) shl 16) or
      ((bytes[off + 2].toLong() and 0xFF) shl 8) or
      (bytes[off + 3].toLong() and 0xFF)

  private fun java.io.InputStream.readFully(buffer: ByteArray) {
    var read = 0
    while (read < buffer.size) {
      val n = read(buffer, read, buffer.size - read)
      if (n < 0) return
      read += n
    }
  }
}
