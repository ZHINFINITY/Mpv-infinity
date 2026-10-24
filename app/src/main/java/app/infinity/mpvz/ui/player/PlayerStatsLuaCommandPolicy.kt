/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * stats.lua starts hidden on a new core. [display-stats-toggle] inverts that state, so Off after an
 * interpolation restart would turn a vanished overlay back on. Command choice follows whether lua
 * is actually shown, never the persisted page alone.
 */
internal object PlayerStatsLuaCommandPolicy {
  const val VISIBLE_PROPERTY = "user-data/mpvrx/stats-lua-visible"
  const val VISIBLE_YES = "yes"
  const val VISIBLE_NO = "no"

  data class Decision(
    val toggleLua: Boolean = false,
    val displayPage: Int? = null,
    val luaVisible: Boolean? = null,
    val disableConsole: Boolean = false,
    val enableConsole: Boolean = false,
  )

  fun isLuaShown(): Boolean =
    PlaybackSession.getPropertyString(VISIBLE_PROPERTY) == VISIBLE_YES

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

  fun forStartup(page: Int): Decision? =
    if (page in 1..5) Decision(toggleLua = true, displayPage = page, luaVisible = true) else null

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
