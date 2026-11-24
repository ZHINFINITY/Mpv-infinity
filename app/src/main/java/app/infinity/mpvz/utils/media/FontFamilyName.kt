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
      if (header[0] != 0x00.toByte() || (header[1] != 0x01.toByte() && header[1] != 0x4F.toByte())) {
        return null
      } // sfnt version: 0x00010000 (TrueType) or 0x4F54544F ("OTTO", CFF OpenType)
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

      // Parse the name table at its real offset within the file; reading from file
      // offset 0 mis-parsed the sfnt header as the name-table header.
      val name = ByteArray((file.length() - nameOffset).toInt().coerceAtLeast(0))
      var skipped = 0L
      while (skipped < nameOffset) {
        val n = input.skip(nameOffset - skipped)
        if (n <= 0L) return null
        skipped += n
      }
      if (name.isNotEmpty() && input.read(name) != name.size) return null
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

  @Volatile private var familyByFile: Map<String, String> = emptyMap()

  /**
   * Resolves a stored font preference (which may be a file name like "Anton-Regular" or a
   * real family name) to the family name inside the TTF. mpv's libass matches `sub-font`
   * against family names only; an unresolved value makes fontconfig fall back to an
   * arbitrary font, which renders with wrong metrics (e.g. collapsed spaces) once
   * `sub-ass-override` re-styles the subs.
   */
  @android.annotation.SuppressLint("DiscouragedApi")
  fun resolve(fontsDir: java.io.File?, preferred: String): String {
    val wanted = preferred.trim()
    if (wanted.isEmpty() || fontsDir == null || !fontsDir.isDirectory) return wanted
    val cached = familyByFile
    val map =
      if (cached.isNotEmpty()) cached
      else {
        val built =
          fontsDir
            .listFiles { f -> f.isFile && f.extension.lowercase() in setOf("ttf", "otf") }
            .orEmpty()
            .mapNotNull { f -> read(f)?.let { family -> f.name.lowercase() to family } }
            .toMap()
        familyByFile = built
        built
      }
    map[wanted.lowercase()]?.let { return it }
    map["${wanted.lowercase()}.ttf"]?.let { return it }
    map["${wanted.lowercase()}.otf"]?.let { return it }
    return wanted
  }
}
