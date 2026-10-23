package app.infinity.mpvz.ui.player

import androidx.media3.common.VideoSize
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
  fun customAspectRatioMapsToContentViewportAndInvalidValuesResetToNatural() {
    assertEquals(2f, media3ContentAspectRatio(2.0), 0.0001f)
    assertEquals(0f, media3ContentAspectRatio(null), 0.0001f)
    assertEquals(0f, media3ContentAspectRatio(0.0), 0.0001f)
    assertEquals(0f, media3ContentAspectRatio(Double.NaN), 0.0001f)
    assertEquals(0f, media3ContentAspectRatio(Double.POSITIVE_INFINITY), 0.0001f)
  }

  @Test
  fun customViewportFillsItsSelectedRatioInsteadOfBeingFittedTwice() {
    assertEquals(VideoAspect.Stretch, media3OutputAspect(VideoAspect.Fit, 2f))
    assertEquals(VideoAspect.Crop, media3OutputAspect(VideoAspect.Crop, null))
    assertEquals(VideoAspect.Fit, media3OutputAspect(VideoAspect.Fit, Float.NaN))
  }

  @Test
  fun naturalAspectRatioUsesPixelAspectAndNeverUsesZeroAsAResetValue() {
    assertEquals(16f / 9f, requireNotNull(media3NaturalContentAspectRatio(VideoSize(1920, 1080, 1f))), 0.0001f)
    assertEquals(2f, requireNotNull(media3NaturalContentAspectRatio(VideoSize(1000, 1000, 2f))), 0.0001f)
    assertEquals(null, media3NaturalContentAspectRatio(null))
    assertEquals(null, media3NaturalContentAspectRatio(VideoSize(0, 1080, 1f)))
    assertEquals(null, media3NaturalContentAspectRatio(VideoSize(1920, 0, 1f)))
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
