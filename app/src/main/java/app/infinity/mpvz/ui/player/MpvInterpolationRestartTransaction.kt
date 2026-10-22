/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/** Exact live state, not the rounded database resume position or another queue item. */
internal data class MpvInterpolationRestartState(
  val mediaId: String,
  val positionSeconds: Double,
  val paused: Boolean,
  val tracks: Map<String, String>,
  val doubleProperties: Map<String, Double>,
  val stringProperties: Map<String, String>,
  val muted: Boolean,
  val externalSubtitles: List<String>,
)

/**
 * Scoped to the user-authorized MPV interpolation shortcuts. Native never uses this transaction.
 * Core reconfiguration and load must be one non-suspending owner-checked commit in the host;
 * every asynchronous boundary is followed by another media/owner/generation check.
 */
internal object MpvInterpolationRestartTransaction {
  interface Host {
    fun isCurrent(state: MpvInterpolationRestartState): Boolean
    suspend fun freeze(state: MpvInterpolationRestartState): Boolean
    suspend fun reconfigureAndLoad(state: MpvInterpolationRestartState): Long?
    suspend fun awaitAndRestore(state: MpvInterpolationRestartState, generation: Long): Boolean
  }

  suspend fun run(state: MpvInterpolationRestartState, host: Host): Boolean {
    if (!state.positionSeconds.isFinite() || state.positionSeconds < 0.0 ||
      state.mediaId.isBlank() || !host.isCurrent(state)) return false
    if (!host.freeze(state) || !host.isCurrent(state)) return false
    val generation = host.reconfigureAndLoad(state) ?: return false
    if (!host.isCurrent(state)) return false
    return host.awaitAndRestore(state, generation) && host.isCurrent(state)
  }
}
