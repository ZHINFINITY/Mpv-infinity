package app.infinity.mpvz.ui.player

import android.hardware.display.DisplayManager
import android.view.Surface
import androidx.annotation.RequiresApi

/** Maps Android's explicit user preference to the display-frame-rate switch policy. */
internal object Media3FlowFrameRatePolicy {
  @RequiresApi(31)
  fun surfaceChangeStrategy(matchContentFrameRatePreference: Int): Int =
    if (matchContentFrameRatePreference == DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS) {
      Surface.CHANGE_FRAME_RATE_ALWAYS
    } else {
      Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
    }
}
