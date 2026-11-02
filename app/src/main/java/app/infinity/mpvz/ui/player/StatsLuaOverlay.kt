package app.infinity.mpvz.ui.player

/**
 * stats.lua only offers a toggle, so sending it blindly flips whatever state MPV is in. That is
 * how music showed the stats page: a fresh music session starts with stats off, and the music
 * page's "hide" toggle turned them on (then flipped them on every song change).
 *
 * Every stats.lua toggle in the app goes through here, so the app always knows whether the
 * overlay is up and music can ask for "hidden" without ever showing it.
 */
object StatsLuaOverlay {
  /** Whether stats.lua is currently visible in the live MPV core. */
  val shown: Boolean
    get() = PlayerStatsLuaCommandPolicy.isLuaShown()

  /** A new MPV core has fresh script state: nothing is on screen. */
  fun onCoreStarted() {
    PlayerStatsLuaCommandPolicy.onCoreRecreated()
  }

  /** Hides the overlay if it is up; never turns it on. */
  fun hide() {
    PlayerStatsLuaCommandPolicy.apply(
      PlayerStatsLuaCommandPolicy.forMusicHide(
        luaShown = shown,
        consoleOpen = false,
      ),
    )
  }

  /** Shows stats page [page] (1..5), turning the overlay on only if it is not already up. */
  fun show(page: Int) {
    if (page !in 1..5) return
    PlayerStatsLuaCommandPolicy.apply(
      PlayerStatsLuaCommandPolicy.Decision(
        toggleLua = !shown,
        displayPage = page,
        luaVisible = true,
      ),
    )
  }
}
