/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.data.network.proxy

internal data class HttpByteRange(
  val start: Long,
  val endInclusive: Long,
) {
  val length: Long = endInclusive - start + 1L

  companion object {
    private val syntax = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE)

    /** Returns the first byte for a partial range when total length is unavailable. */
    fun parseStart(header: String): Long? {
      val match = syntax.matchEntire(header.trim()) ?: return null
      val startText = match.groupValues[1]
      val endText = match.groupValues[2]
      if (startText.isEmpty() || startText.toLongOrNull() == null) return null
      val start = startText.toLongOrNull() ?: return null
      val requestedEnd = endText.toLongOrNull()
      if (requestedEnd != null && requestedEnd < start) return null
      return start
    }

    /** Returns null for malformed, multiple, overflowing, or unsatisfiable ranges. */
    fun parse(
      header: String,
      completeLength: Long,
    ): HttpByteRange? {
      if (completeLength <= 0L) return null
      val match = syntax.matchEntire(header.trim()) ?: return null
      val startText = match.groupValues[1]
      val endText = match.groupValues[2]
      if (startText.isEmpty() && endText.isEmpty()) return null

      if (startText.isEmpty()) {
        val suffixLength = endText.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val start = (completeLength - suffixLength).coerceAtLeast(0L)
        return HttpByteRange(start, completeLength - 1L)
      }

      val start = startText.toLongOrNull() ?: return null
      if (start >= completeLength) return null
      val requestedEnd =
        if (endText.isEmpty()) {
          completeLength - 1L
        } else {
          endText.toLongOrNull() ?: return null
        }
      if (requestedEnd < start) return null
      return HttpByteRange(start, requestedEnd.coerceAtMost(completeLength - 1L))
    }
  }
}

