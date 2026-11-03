/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * stats.lua starts hidden on a new core. `display-page-N` selects a page but does not toggle the
 * persistent overlay; `display-stats-toggle` owns persistent visibility. The bundled script keeps
 * its overlay across video reconfiguration, so only a newly created core resets our live state.
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
  )

  fun isLuaShown(): Boolean =
    PlaybackSession.getPropertyString(VISIBLE_PROPERTY) == VISIBLE_YES

  fun onCoreRecreated() {
    PlaybackSession.setPropertyString(STARTUP_APPLIED_PROPERTY, "no")
    PlaybackSession.setPropertyString(VISIBLE_PROPERTY, VISIBLE_NO)
  }

  fun forChip(toPage: Int, luaShown: Boolean, consoleOpen: Boolean): Decision {
    val disableConsole = toPage != 7 && consoleOpen
    return when (toPage) {
      0, 6 -> Decision(
        toggleLua = luaShown,
        luaVisible = false,
        disableConsole = disableConsole,
      )
      7 -> Decision(
        toggleLua = luaShown,
        luaVisible = false,
        enableConsole = !consoleOpen,
      )
      else -> Decision(
        toggleLua = !luaShown,
        displayPage = toPage,
        luaVisible = true,
        disableConsole = disableConsole,
      )
    }
  }

  fun forStartup(page: Int, luaShown: Boolean): Decision? =
    if (page in 1..5) Decision(toggleLua = !luaShown, displayPage = page, luaVisible = true) else null

  fun forVideoResume(page: Int, luaShown: Boolean): Decision? =
    if (page in 1..5 && !luaShown) Decision(toggleLua = true, displayPage = page, luaVisible = true) else null

  fun forMusicHide(luaShown: Boolean, consoleOpen: Boolean): Decision =
    forChip(toPage = 0, luaShown = luaShown, consoleOpen = consoleOpen)

  fun shouldWaitForPlaybackBeforeRestore(fileLoaded: Boolean, timePosFinite: Boolean): Boolean =
    fileLoaded && timePosFinite

  fun shouldReapplyAfterVoReconfig(luaRestored: Boolean, alreadyReapplied: Boolean): Boolean =
    luaRestored && !alreadyReapplied

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
    decision.luaVisible?.let { visible ->
      PlaybackSession.setPropertyString(VISIBLE_PROPERTY, if (visible) VISIBLE_YES else VISIBLE_NO)
    }
  }
}
