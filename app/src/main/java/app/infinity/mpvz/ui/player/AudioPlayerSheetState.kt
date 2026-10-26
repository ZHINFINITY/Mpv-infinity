package app.infinity.mpvz.ui.player

/** Shared progress and collapse behavior used by the full audio player in either sheet host. */
interface AudioPlayerSheetState {
  /** 0 rests on the mini player; 1 fills the window. */
  val progress: Float

  /** Collapse the sheet if it is visible; return false when it is already collapsed. */
  fun collapse(): Boolean
}
