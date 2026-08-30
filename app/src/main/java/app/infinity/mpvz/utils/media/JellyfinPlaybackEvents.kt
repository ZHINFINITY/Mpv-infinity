/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.utils.media

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Events emitted after Jellyfin accepts a playback stop report. */
object JellyfinPlaybackEvents {
  private val _completed =
    MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  val completed: SharedFlow<Unit> = _completed

  fun notifyCompleted() {
    _completed.tryEmit(Unit)
  }
}

