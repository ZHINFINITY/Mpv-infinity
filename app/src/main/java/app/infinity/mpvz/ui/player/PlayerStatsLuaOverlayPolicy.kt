package app.infinity.mpvz.ui.player

/**
 * stats.lua only exposes a toggle plus display-page. Two owners (the More sheet and this
 * policy) toggling on Off leaves the OSD up; display-page without replacing the last page
 * stacks page 2 on page 1, especially after interpolation recreates the core.
 */
internal object PlayerStatsLuaOverlayPolicy {
  data class Command(val args: List<String>)
  data class Input(
    val requestedPage: Int,
    val currentlyVisible: Boolean,
    val currentlyPage: Int,
    val sheetOrPanelOpen: Boolean,
    val isMusic: Boolean,
  )
  data class Result(
    val visible: Boolean,
    val page: Int,
    val commands: List<Command>,
  )

  private val hideToggle = Command(listOf("script-binding", "stats/display-stats-toggle"))

  private fun showPage(page: Int) = Command(listOf("script-binding", "stats/display-page-$page"))

  fun decide(input: Input): Result {
    val wantPage = input.requestedPage.takeIf { it in 1..5 }
    val wantVisible = wantPage != null && !input.sheetOrPanelOpen && !input.isMusic
    val commands = mutableListOf<Command>()
    if (!wantVisible) {
      if (input.currentlyVisible) commands += hideToggle
      return Result(visible = false, page = 0, commands = commands)
    }
    if (!input.currentlyVisible) {
      commands += showPage(wantPage!!)
    } else if (input.currentlyPage != wantPage) {
      commands += showPage(wantPage!!)
    }
    return Result(visible = true, page = wantPage!!, commands = commands)
  }
}
