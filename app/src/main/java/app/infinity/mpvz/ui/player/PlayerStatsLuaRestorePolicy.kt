/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * Readiness guards for restoring the persisted stats.lua page after a newly created MPV core or
 * controlled interpolation reload. Actual show/hide state and commands remain owned by
 * [StatsLuaOverlay], matching the public v2.2.3 implementation.
 */
internal object PlayerStatsLuaRestorePolicy {
  const val STARTUP_APPLIED_PROPERTY = "user-data/mpvrx/startup-stats-applied"
  const val STARTUP_APPLIED_YES = "yes"

  fun onCoreRecreated() {
    PlaybackSession.setPropertyString(STARTUP_APPLIED_PROPERTY, "no")
  }

  fun shouldWaitForPlaybackBeforeRestore(fileLoaded: Boolean, timePosFinite: Boolean): Boolean =
    fileLoaded && timePosFinite

  fun shouldReapplyAfterVoReconfig(luaRestored: Boolean, alreadyReapplied: Boolean): Boolean =
    luaRestored && !alreadyReapplied
}
