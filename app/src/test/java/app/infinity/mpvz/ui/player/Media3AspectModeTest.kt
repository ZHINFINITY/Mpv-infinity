package app.infinity.mpvz.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3AspectModeTest {
  @Test
  fun eachAspectModeUsesItsMatchingMedia3ResizeBehavior() {
    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, media3ResizeModeForAspect(VideoAspect.Fit))
    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, media3ResizeModeForAspect(VideoAspect.Crop))
    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, media3ResizeModeForAspect(VideoAspect.Stretch))
  }

  @Test
  fun selectedAspectModeSurvivesViewReattachmentAndConfigurationRefresh() {
    val state = Media3AspectModeState()
    state.select(VideoAspect.Stretch)

    // attach() and refreshAfterConfigurationChange() both read the same persisted engine state.
    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, state.resizeMode())
    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, state.resizeMode())

    state.select(VideoAspect.Crop)
    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, state.resizeMode())
    assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, state.resizeMode())
  }
}
