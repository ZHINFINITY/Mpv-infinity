/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.domain.thumbnail

/** Keeps decoded artwork bounded independently of how many folders a user visits. */
internal object ThumbnailCachePolicy {
  const val MinBytes = 8L * 1024L * 1024L
  const val MaxBytes = 64L * 1024L * 1024L

  fun maxBytes(memoryClassBytes: Long, lowRam: Boolean): Long =
    (memoryClassBytes / if (lowRam) 12L else 8L).coerceIn(MinBytes, MaxBytes)

  fun trimBytes(currentBytes: Long, runningCritical: Boolean): Long =
    if (runningCritical) 0L else currentBytes / 2L
}
