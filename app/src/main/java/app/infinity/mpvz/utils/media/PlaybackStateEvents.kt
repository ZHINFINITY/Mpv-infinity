/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.utils.media

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object PlaybackStateEvents {
  private val _changes =
    MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val changes: SharedFlow<String> = _changes

  fun notifyChanged(mediaIdentifier: String) {
    _changes.tryEmit(mediaIdentifier)
  }
}
