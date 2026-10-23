/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes playback-state writes and prevents an older capture overwriting a newer one. */
internal object PlaybackStateSaveCoordinator {
  private val sequence = AtomicLong(0L)
  private val mutex = Mutex()
  private val latestSequences = mutableMapOf<String, Long>()

  fun nextSequence(): Long = sequence.incrementAndGet()

  suspend fun persistIfNewest(
    mediaIdentifier: String,
    sequence: Long,
    write: suspend () -> Unit,
  ): Boolean = mutex.withLock {
    val latest = latestSequences[mediaIdentifier]
    if (latest != null && sequence < latest) return@withLock false
    latestSequences[mediaIdentifier] = sequence
    write()
    true
  }
}
