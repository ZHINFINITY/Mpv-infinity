package app.infinity.mpvz.ui.player

import android.hardware.display.DisplayManager
import android.view.Surface
import androidx.annotation.RequiresApi
import org.junit.Assert.assertEquals
import org.junit.Test

@RequiresApi(31)
class Media3FlowFrameRatePolicyTest {
  @Test
  fun alwaysPreferenceAllowsNonSeamlessModeSwitch() {
    assertEquals(
      Surface.CHANGE_FRAME_RATE_ALWAYS,
      Media3FlowFrameRatePolicy.surfaceChangeStrategy(DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS),
    )
  }

  @Test
  fun otherPreferencesRemainSeamlessOnly() {
    val preferences = intArrayOf(
      DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN,
      DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER,
      DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY,
    )

    preferences.forEach { preference ->
      assertEquals(
        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
        Media3FlowFrameRatePolicy.surfaceChangeStrategy(preference),
      )
    }
  }
}
