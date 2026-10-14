/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player

import java.util.concurrent.atomic.AtomicLong

internal data class PreparedPlaybackLaunch(
  val token: Long,
  val items: List<PlaybackItem>,
  val currentIndex: Int,
  val isExplicitQueue: Boolean,
  val isM3u: Boolean,
)

internal sealed interface PreparedPlaybackLaunchResult {
  data class Accepted(val launch: PreparedPlaybackLaunch) : PreparedPlaybackLaunchResult

  data object Missing : PreparedPlaybackLaunchResult

  data object Stale : PreparedPlaybackLaunchResult
}

/** Holds one immutable in-process queue until PlayerActivity accepts the matching launch Intent. */
internal object PreparedPlaybackLaunchStore {
  private val sequence = AtomicLong()
  private val lock = Any()
  private var pending: PreparedPlaybackLaunch? = null
  private var lastConsumedToken = 0L

  fun stage(
    items: List<PlaybackItem>,
    currentIndex: Int,
    isExplicitQueue: Boolean = true,
    isM3u: Boolean = false,
  ): Long {
    require(items.isNotEmpty()) { "A prepared playback launch requires at least one item" }
    synchronized(lock) {
      val token = sequence.incrementAndGet()
      pending =
        PreparedPlaybackLaunch(
          token = token,
          items = items.map { item -> item.copy(headers = item.headers.toMap()) },
          currentIndex = currentIndex.coerceIn(items.indices),
          isExplicitQueue = isExplicitQueue,
          isM3u = isM3u,
        )
      return token
    }
  }

  fun consume(token: Long): PreparedPlaybackLaunchResult {
    if (token <= 0L) return PreparedPlaybackLaunchResult.Missing
    synchronized(lock) {
      val launch = pending
      if (launch?.token == token) {
        pending = null
        lastConsumedToken = token
        return PreparedPlaybackLaunchResult.Accepted(launch)
      }
      return if (token <= lastConsumedToken || token < sequence.get()) {
        PreparedPlaybackLaunchResult.Stale
      } else {
        // The process may have been recreated after Android retained the Intent. Its URI remains
        // a valid standalone fallback even though the process-local queue no longer exists.
        PreparedPlaybackLaunchResult.Missing
      }
    }
  }
}