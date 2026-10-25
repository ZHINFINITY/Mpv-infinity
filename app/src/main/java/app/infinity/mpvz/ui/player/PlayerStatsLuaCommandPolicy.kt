/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * Copied from mpvRx MoreSheet and MPVView.applyStartupStatistics.
 * [display-stats-toggle] shows or hides a persistent overlay. [display-page-N] then
 * selects that page (page-switch while shown; oneshot if still hidden). Off hides with
 * the same toggle when the previous chip was a lua page, or when the overlay is shown.
 * Empty OSD clear covers leftover oneshot text after interpolation vo-reconfig.
 */
internal object PlayerStatsLuaCommandPolicy {
  const val VISIBLE_PROPERTY = "user-data/mpvrx/stats-lua-visible"
  const val VISIBLE_YES = "yes"
  const val VISIBLE_NO = "no"
  const val STARTUP_APPLIED_PROPERTY = "user-data/mpvrx/startup-stats-applied"
  const val STARTUP_APPLIED_YES = "yes"

  data class Decision(
    val toggleLua: Boolean = false,
    val displayPage: Int? = null,
    val luaVisible: Boolean? = null,
    val disableConsole: Boolean = false,
    val enableConsole: Boolean = false,
    val clearOsd: Boolean = false,
  )

  fun isLuaShown(): Boolean =
    PlaybackSession.getPropertyString(VISIBLE_PROPERTY) == VISIBLE_YES

  fun onCoreRecreated() {
    PlaybackSession.setPropertyString(STARTUP_APPLIED_PROPERTY, "no")
    PlaybackSession.setPropertyString(VISIBLE_PROPERTY, VISIBLE_NO)
  }

  fun onVoReconfig() {
    PlaybackSession.setPropertyString(VISIBLE_PROPERTY, VISIBLE_NO)
  }

  fun forChip(toPage: Int, previousPage: Int, luaShown: Boolean, consoleOpen: Boolean): Decision {
    val disableConsole = toPage != 7 && consoleOpen
    return when (toPage) {
      0, 6 -> Decision(
        toggleLua = previousPage in 1..5 || luaShown,
        luaVisible = false,
        disableConsole = disableConsole,
        clearOsd = true,
      )
      7 -> Decision(
        toggleLua = previousPage in 1..5 || luaShown,
        luaVisible = false,
        enableConsole = !consoleOpen,
        clearOsd = true,
      )
      else -> Decision(
        toggleLua = previousPage == 0 || previousPage == 6 || previousPage == 7,
        displayPage = toPage,
        luaVisible = true,
        disableConsole = disableConsole,
      )
    }
  }

  fun forStartup(page: Int): Decision? =
    if (page in 1..5) Decision(toggleLua = true, displayPage = page, luaVisible = true) else null

  fun forVideoResume(page: Int, luaShown: Boolean): Decision? =
    if (page in 1..5 && !luaShown) Decision(toggleLua = true, displayPage = page, luaVisible = true) else null

  fun forMusicHide(luaShown: Boolean, consoleOpen: Boolean): Decision =
    forChip(toPage = 0, previousPage = 0, luaShown = luaShown, consoleOpen = consoleOpen)

  fun shouldWaitForPlaybackBeforeRestore(fileLoaded: Boolean, timePosFinite: Boolean): Boolean =
    fileLoaded && timePosFinite

  fun shouldReapplyAfterVoReconfig(luaRestored: Boolean, alreadyReapplied: Boolean): Boolean =
    false

  fun apply(decision: Decision) {
    if (decision.disableConsole) {
      PlaybackSession.command("script-message-to", "console", "disable")
    }
    if (decision.toggleLua) {
      PlaybackSession.command("script-binding", "stats/display-stats-toggle")
    }
    decision.displayPage?.let { page ->
      PlaybackSession.command("script-binding", "stats/display-page-$page")
    }
    if (decision.enableConsole) {
      PlaybackSession.command("script-binding", "console/enable")
    }
    if (decision.clearOsd) {
      // Extra hide for leftover oneshot OSD. Persistent overlays are already closed by toggle.
      PlaybackSession.command("show-text", "", "0")
    }
    decision.luaVisible?.let { visible ->
      PlaybackSession.setPropertyString(VISIBLE_PROPERTY, if (visible) VISIBLE_YES else VISIBLE_NO)
    }
  }
}
