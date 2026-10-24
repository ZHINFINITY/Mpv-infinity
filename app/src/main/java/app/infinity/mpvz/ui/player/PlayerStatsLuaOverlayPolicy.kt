package app.infinity.mpvz.ui.player

/**
 * Single owner for stats.lua OSD commands.
 *
 * stats.lua's display-page-N adds a page; it does not replace an already drawn
 * overlay. One display-stats-toggle only hides one layer. Chip clicks therefore
 * hide every drawn layer first, then show at most one page. Interpolation/core
 * rebind must do the same reset before restoring a persisted page.
 *
 * MoreSheet is the chip sender. PlayerActivity must not emit competing lua
 * commands while More is open.
 */
internal object PlayerStatsLuaOverlayPolicy {
  data class Command(val args: List<String>)
  data class Input(
    val requestedPage: Int,
    val currentlyVisible: Boolean,
    val currentlyPage: Int,
    val overlayDepth: Int = if (currentlyVisible) 1 else 0,
    val sheetOrPanelOpen: Boolean = false,
    val isMusic: Boolean = false,
    val forceReset: Boolean = false,
    val senderIsMoreSheet: Boolean = false,
  )
  data class Result(
    val visible: Boolean,
    val page: Int,
    val overlayDepth: Int,
    val commands: List<Command>,
  )

  /** Shared depth across More chips and Activity restore so Off can hide stacks. */
  var overlayDepth: Int = 0
    private set

  private val hideToggle = Command(listOf("script-binding", "stats/display-stats-toggle"))

  private fun showPage(page: Int) = Command(listOf("script-binding", "stats/display-page-$page"))

  fun luaPage(page: Int) = page in 1..5

  fun record(result: Result) {
    overlayDepth = result.overlayDepth.coerceIn(0, 5)
  }

  fun reset() {
    overlayDepth = 0
  }

  /**
   * Hide every stats.lua overlay we believe is drawn. After interpolation the
   * Kotlin flag can lie, so [forceReset] and a persisted lua page still hide
   * at least once. Never toggle past depth 0: an extra toggle would turn the
   * OSD back on.
   */
  fun hideAllCommands(
    overlayDepth: Int,
    currentlyVisible: Boolean,
    persistedLuaPage: Boolean = false,
    forceReset: Boolean = false,
  ): List<Command> {
    val known = overlayDepth.coerceAtLeast(if (currentlyVisible) 1 else 0)
    // Extra toggles on a clean core turn stats.lua back on and then stack.
    val hides = known.coerceAtMost(5)
    return List(hides) { hideToggle }
  }

  fun decide(input: Input): Result {
    if (input.senderIsMoreSheet) {
      // Activity must not compete with chip clicks. Tracking is updated by
      // [applyChip] which MoreSheet runs before the preference write.
      return Result(
        visible = luaPage(input.requestedPage),
        page = input.requestedPage.takeIf { luaPage(it) } ?: 0,
        overlayDepth = if (luaPage(input.requestedPage)) 1 else 0,
        commands = emptyList(),
      )
    }

    val wantLua = luaPage(input.requestedPage) && !input.sheetOrPanelOpen && !input.isMusic
    if (
      wantLua &&
        !input.forceReset &&
        input.currentlyVisible &&
        input.overlayDepth <= 1 &&
        input.currentlyPage == input.requestedPage
    ) {
      return Result(visible = true, page = input.requestedPage, overlayDepth = 1, commands = emptyList())
    }
    val hide = hideAllCommands(
      overlayDepth = input.overlayDepth,
      currentlyVisible = input.currentlyVisible,
      persistedLuaPage = luaPage(input.currentlyPage) || luaPage(input.requestedPage),
      forceReset = input.forceReset || input.isMusic || !wantLua,
    )
    if (!wantLua) {
      return Result(visible = false, page = 0, overlayDepth = 0, commands = hide)
    }
    val wantPage = input.requestedPage
    val alreadyExact =
      !input.forceReset &&
        input.currentlyVisible &&
        input.overlayDepth <= 1 &&
        input.currentlyPage == wantPage &&
        hide.isEmpty()
    if (alreadyExact) {
      return Result(visible = true, page = wantPage, overlayDepth = 1, commands = emptyList())
    }
    return Result(
      visible = true,
      page = wantPage,
      overlayDepth = 1,
      commands = hide + showPage(wantPage),
    )
  }

  /**
   * Chip path used only by MoreSheet. Hide every drawn lua overlay, then
   * display-page-N so page 2 never paints on page 1. Off/page 6/console leave
   * lua fully hidden.
   */
  fun decideChip(fromPage: Int, toPage: Int, overlayDepth: Int, currentlyVisible: Boolean): Result {
    if (fromPage == toPage && luaPage(toPage) && currentlyVisible && overlayDepth <= 1) {
      return Result(visible = true, page = toPage, overlayDepth = 1, commands = emptyList())
    }
    val hide = hideAllCommands(
      overlayDepth = overlayDepth,
      currentlyVisible = currentlyVisible,
      persistedLuaPage = luaPage(fromPage),
      forceReset = luaPage(fromPage) || overlayDepth > 0 || currentlyVisible,
    )
    if (!luaPage(toPage)) {
      return Result(visible = false, page = 0, overlayDepth = 0, commands = hide)
    }
    return Result(
      visible = true,
      page = toPage,
      overlayDepth = 1,
      commands = hide + showPage(toPage),
    )
  }

  fun applyChip(fromPage: Int, toPage: Int): Result {
    val currentlyVisible = overlayDepth > 0
    val result = decideChip(
      fromPage = fromPage,
      toPage = toPage,
      overlayDepth = overlayDepth,
      currentlyVisible = currentlyVisible,
    )
    record(result)
    return result
  }
}
