package app.infinity.mpvz.ui.player

/** Shared decision for the persisted statistics page and its per-engine overlay. */
internal object PlayerStatsOverlayPolicy {
  enum class Engine { MPV, NATIVE }
  enum class Action { HIDE, SHOW_PAGE }
  data class Input(
    val enabled: Boolean,
    val page: Int,
    val engine: Engine,
    val isAudioOnly: Boolean,
    val musicMode: Boolean,
    val mediaTypeKnown: Boolean,
  )
  data class Decision(val action: Action, val page: Int? = null) {
    val visible: Boolean get() = action == Action.SHOW_PAGE
  }

  fun decide(input: Input): Decision {
    val composePage = when (input.engine) {
      Engine.NATIVE -> input.page in 1..6
      Engine.MPV -> input.page == 6
    }
    val canShow = input.enabled && composePage && input.mediaTypeKnown &&
      !input.isAudioOnly && !input.musicMode
    return if (canShow) Decision(Action.SHOW_PAGE, input.page) else Decision(Action.HIDE)
  }
}
