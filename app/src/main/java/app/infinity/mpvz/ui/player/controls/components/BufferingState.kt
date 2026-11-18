/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

package app.infinity.mpvz.ui.player.controls.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.infinity.mpvz.ui.player.PlaybackSession
import kotlinx.coroutines.delay

internal object BufferingVisibilityDebouncePolicy {
  const val SHOW_DELAY_MS = 350L
  const val HIDE_DELAY_MS = 300L

  fun delayFor(shouldShow: Boolean): Long = if (shouldShow) SHOW_DELAY_MS else HIDE_DELAY_MS

  fun isVisible(enabled: Boolean, debouncedVisible: Boolean): Boolean = enabled && debouncedVisible
}

/** Local opens are not cache stalls; explicit pre-MPV torrent waits remain visible. */
internal object MpvBufferingVisibilityPolicy {
  fun isStalled(
    enabled: Boolean,
    forceVisible: Boolean,
    suppressStartupStalls: Boolean,
    paused: Boolean?,
    eofReached: Boolean?,
    idleActive: Boolean?,
    isCacheStall: Boolean,
    coreIdle: Boolean?,
  ): Boolean {
    if (!enabled) return false
    if (forceVisible) return true
    if (suppressStartupStalls) return false
    return paused != true &&
      eofReached != true &&
      idleActive != true &&
      (isCacheStall || coreIdle == true)
  }
}

/** Debounces short engine load/seek transitions without delaying a user disabling the indicator. */
@Composable
internal fun rememberDebouncedBufferingVisibility(
  enabled: Boolean,
  stalled: Boolean,
): Boolean {
  var visible by remember { mutableStateOf(false) }
  LaunchedEffect(enabled, stalled) {
    if (!enabled) {
      visible = false
      return@LaunchedEffect
    }
    if (visible == stalled) return@LaunchedEffect
    delay(BufferingVisibilityDebouncePolicy.delayFor(stalled))
    visible = stalled
  }
  return BufferingVisibilityDebouncePolicy.isVisible(enabled, visible)
}

/**
 * Resolved buffering state for the player overlay.
 *
 * @param visible whether the spinner should be on screen right now.
 * @param isCacheStall mpv paused playback specifically because the cache ran dry.
 * @param cachePercent mpv's fill progress toward the unpause threshold, when it reports one.
 * @param cacheSeconds how many seconds of media the demuxer currently holds.
 */
internal data class BufferingState(
  val visible: Boolean = false,
  val isCacheStall: Boolean = false,
  val cachePercent: Int? = null,
  val cacheSeconds: Double? = null,
)

/**
 * Derives "is playback actually stalled?" from mpv's core state rather than from a single property.
 *
 * `paused-for-cache` only becomes true once mpv has already given up and stopped the clock, so it
 * misses slow opens and demuxer stalls. `core-idle` covers those: it is set whenever the core is not
 * producing output. Subtracting the cases where that is expected (user paused, end of file, nothing
 * loaded) leaves the precise condition.
 */
@Composable
internal fun rememberBufferingState(
  enabled: Boolean,
  forceVisible: Boolean = false,
  suppressStartupStalls: Boolean = false,
): BufferingState {
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val coreIdle by PlaybackSession.propBoolean["core-idle"].collectAsState()
  val pausedForCache by PlaybackSession.propBoolean["paused-for-cache"].collectAsState()
  val eofReached by PlaybackSession.propBoolean["eof-reached"].collectAsState()
  val idleActive by PlaybackSession.propBoolean["idle-active"].collectAsState()
  val cacheBufferingState by PlaybackSession.propInt["cache-buffering-state"].collectAsState()
  val demuxerCacheDuration by PlaybackSession.propDouble["demuxer-cache-duration"].collectAsState()

  val isCacheStall = pausedForCache == true
  val stalled = MpvBufferingVisibilityPolicy.isStalled(
    enabled = enabled,
    forceVisible = forceVisible,
    suppressStartupStalls = suppressStartupStalls,
    paused = paused,
    eofReached = eofReached,
    idleActive = idleActive,
    isCacheStall = isCacheStall,
    coreIdle = coreIdle,
  )

  val visible = rememberDebouncedBufferingVisibility(enabled = enabled, stalled = stalled)

  return BufferingState(
    visible = visible,
    isCacheStall = isCacheStall,
    cachePercent = cacheBufferingState?.takeIf { it in 0..100 },
    cacheSeconds = demuxerCacheDuration?.takeIf { it.isFinite() && it > 0.0 },
  )
}
