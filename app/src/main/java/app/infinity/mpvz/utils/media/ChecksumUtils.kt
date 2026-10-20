/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.utils.media

import java.util.zip.CRC32

object ChecksumUtils {
  /**
   * Generates a CRC32 checksum for the given text.
   * Returns the hex string representation.
   */
  fun getCRC32(text: String): String {
    val crc = CRC32()
    crc.update(text.toByteArray(Charsets.UTF_8))
    return java.lang.Long
      .toHexString(crc.value)
      .uppercase()
  }
}
