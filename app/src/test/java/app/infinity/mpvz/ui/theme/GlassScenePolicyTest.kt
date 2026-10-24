/* Copyright (c) 2026 ZHINFINITY. See LICENSE for project-specific terms. */
package app.infinity.mpvz.ui.theme

import org.junit.Assert.*
import org.junit.Test

class GlassScenePolicyTest {
  @Test fun pillarboxCopyKeepsVideoAspectAndExtendsItsEdgesOnlyInTheUi() {
    val crop = GlassScenePolicy.sourceRect(1558, 720, 16.0 / 9.0)!!
    assertEquals(1280, crop.width)
    assertEquals(720, crop.height)
    assertEquals((1558 - crop.width) / 2, crop.left)
    assertEquals(0, crop.top)
  }
  @Test fun letterboxAndFullSurfaceRemainCenteredAndBounded() {
    val crop = GlassScenePolicy.sourceRect(1080, 1920, 16.0 / 9.0)!!
    assertEquals(1080, crop.width)
    assertEquals((1920 - crop.height) / 2, crop.top)
    assertTrue(kotlin.math.abs(crop.width.toDouble() / crop.height - 16.0 / 9.0) < 0.01)
    assertEquals(GlassScenePolicy.SourceRect(0, 0, 1920, 1080), GlassScenePolicy.sourceRect(1920, 1080, 16.0 / 9.0))
  }
  @Test fun invalidSourcesAndAspectsCannotEscapeTheSourceRectangle() {
    assertNull(GlassScenePolicy.sourceRect(0, 100, 1.0))
    assertNull(GlassScenePolicy.sourceRect(100, -1, 1.0))
    for (aspect in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
      assertEquals(GlassScenePolicy.SourceRect(0, 0, 100, 50), GlassScenePolicy.sourceRect(100, 50, aspect))
    }
    val crop = GlassScenePolicy.sourceRect(Int.MAX_VALUE, Int.MAX_VALUE, Double.MIN_VALUE)!!
    assertTrue(crop.width in 1..Int.MAX_VALUE)
    assertTrue(crop.height in 1..Int.MAX_VALUE)
  }
  @Test fun squareArtworkCoversPortraitWithoutStretching() {
    val crop = GlassScenePolicy.coverBounds(512, 512, 360f, 780f)!!
    assertEquals(crop.width, crop.height, 0f)
    assertEquals(780f, crop.height, 0.001f)
    assertEquals((360f - crop.width) / 2f, crop.left, 0.001f)
    assertEquals(0f, crop.top, 0.001f)
  }
  @Test fun rectangularArtworkPreservesItsAspectAndRejectsInvalidGeometry() {
    val crop = GlassScenePolicy.coverBounds(1920, 1080, 360f, 780f)!!
    assertEquals(1920f / 1080f, crop.width / crop.height, 0.001f)
    assertTrue(crop.width >= 360f && crop.height >= 780f)
    assertNull(GlassScenePolicy.coverBounds(0, 512, 360f, 780f))
    assertNull(GlassScenePolicy.coverBounds(512, 512, Float.NaN, 780f))
    assertNull(GlassScenePolicy.coverBounds(512, 512, 360f, -1f))
  }
  @Test fun onlyShortTransientFailuresRetainTheLastGoodFrame() {
    assertTrue(GlassScenePolicy.retainTransientFrame(0))
    assertTrue(GlassScenePolicy.retainTransientFrame(GlassScenePolicy.TransientGraceMs - 1))
    assertFalse(GlassScenePolicy.retainTransientFrame(GlassScenePolicy.TransientGraceMs))
    assertFalse(GlassScenePolicy.retainTransientFrame(-1))
    assertFalse(GlassScenePolicy.retainTransientFrame(Long.MAX_VALUE))
    assertTrue(GlassScenePolicy.TransientGraceMs <= 1000L)
  }

  @Test fun retainedDisplayListIsNeverDrawnWhileRecordingOrAfterInvalidation() {
    assertTrue(GlassScenePolicy.canDrawRecordedScene(false, true))
    assertFalse(GlassScenePolicy.canDrawRecordedScene(true, true))
    assertFalse(GlassScenePolicy.canDrawRecordedScene(false, false))
    // Model rapid pager/tap disposal and re-measurement: every invalid state
    // must remain safe until a fresh successful record publishes it.
    var valid = true
    repeat(10_000) { cycle ->
      valid = false // zero-size/disposed source invalidates retained display list
      assertFalse(GlassScenePolicy.canDrawRecordedScene(false, valid))
      valid = cycle % 2 == 0 // only a completed record may publish a new list
      assertEquals(valid, GlassScenePolicy.canDrawRecordedScene(false, valid))
    }
  }

  @Test fun captureGeometryRejectsTransientAndUnboundedSizes() {
    assertTrue(GlassScenePolicy.validCaptureSize(1f, 1f))
    assertTrue(GlassScenePolicy.validCaptureSize(8192f, 384f))
    assertTrue(GlassScenePolicy.validCaptureSize(4096f, 4096f))
    assertFalse(GlassScenePolicy.validCaptureSize(8192f, 8192f))
    for (size in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 8193f)) {
      assertFalse(GlassScenePolicy.validCaptureSize(size, 384f))
      assertFalse(GlassScenePolicy.validCaptureSize(384f, size))
    }
  }

  @Test fun ambienceKeepsLastFrostedFrameUnlessSurfaceOrLifecycleEnded() {
    assertTrue(GlassScenePolicy.shouldKeepLastFrostedFrame(false, false, false))
    // Background ON_STOP is not a destroy; keep frost so resume is not a clear pane.
    assertTrue(GlassScenePolicy.shouldKeepLastFrostedFrame(lifecycleStopped = true, sourceChanged = false, ineligible = false))
    assertFalse(GlassScenePolicy.shouldKeepLastFrostedFrame(lifecycleStopped = false, sourceChanged = true, ineligible = false))
    assertFalse(GlassScenePolicy.shouldKeepLastFrostedFrame(lifecycleStopped = false, sourceChanged = false, ineligible = true))
  }
}
