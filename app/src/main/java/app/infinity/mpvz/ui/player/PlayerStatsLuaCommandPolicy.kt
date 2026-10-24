/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE for terms.
 */
package app.infinity.mpvz.ui.player

/**
 * stats.lua starts hidden on a new core. [display-page-N] shows that page; if it is already shown,
 * the same binding hides it. Interpolation restore therefore sends only [display-page-N].
 * Off never uses a persisted page as proof that lua is visible.
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

  fun onVoReconfig() {
    PlaybackSession.setPropertyString(VISIBLE_PROPERTY, VISIBLE_NO)
  }

  fun forChip(toPage: Int, previousPage: Int, luaShown: Boolean, consoleOpen: Boolean): Decision {
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
        toggleLua = previousPage == 0 || previousPage == 6 || previousPage == 7,
        displayPage = toPage,
        luaVisible = true,
        disableConsole = disableConsole,
      )
    }
  }

  fun forStartup(page: Int): Decision? =
    if (page in 1..5) Decision(displayPage = page, luaVisible = true) else null

  fun forVideoResume(page: Int, luaShown: Boolean): Decision? =
    if (page in 1..5 && !luaShown) Decision(displayPage = page, luaVisible = true) else null

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
    decision.luaVisible?.let { visible ->
      PlaybackSession.setPropertyString(VISIBLE_PROPERTY, if (visible) VISIBLE_YES else VISIBLE_NO)
    }
  }
}
