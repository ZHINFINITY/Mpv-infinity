package app.infinity.mpvz.ui.player

import app.infinity.mpvz.BuildConfig
import android.util.Log

/**
 * stats.lua only offers a toggle, so sending it blindly flips whatever state MPV is in. That is
 * how music showed the stats page: a fresh music session starts with stats off, and the music
 * page's "hide" toggle turned them on (then flipped them on every song change).
 *
 * Every stats.lua toggle in the app goes through here, so the app always knows whether the
 * overlay is up and music can ask for "hidden" without ever showing it. Visibility is deliberately
 * kept in process-local state, matching the public v2.2.3 implementation; an MPV user-data marker
 * is not authoritative for the Lua script's current toggle state.
 */
object StatsLuaOverlay {
  private const val TAG = "StatsLuaOverlay"

  /** Whether the toggled stats.lua overlay is currently on in the live MPV core. */
  @Volatile
  var shown: Boolean = false
    private set

  /** A new MPV core has fresh script state: nothing is on screen. */
  fun onCoreStarted() {
    shown = false
  }

  /** Hides the overlay if it is up; never turns it on. */
  fun hide() {
    val wasShown = shown
    if (!wasShown) {
      if (BuildConfig.DEBUG) Log.i(TAG, "action=hide shown=false toggleSent=false")
      return
    }
    PlaybackSession.command("script-binding", "stats/display-stats-toggle")
    shown = false
    if (BuildConfig.DEBUG) Log.i(TAG, "action=hide shown=true toggleSent=true")
  }

  /** Shows stats page [page] (1..5), turning the overlay on only if it is not already up. */
  fun show(page: Int) {
    if (page !in 1..5) return
    val wasShown = shown
    if (!wasShown) {
      PlaybackSession.command("script-binding", "stats/display-stats-toggle")
      shown = true
    }
    PlaybackSession.command("script-binding", "stats/display-page-$page")
    if (BuildConfig.DEBUG) Log.i(TAG, "action=show page=$page wasShown=$wasShown toggleSent=${!wasShown}")
  }
}
