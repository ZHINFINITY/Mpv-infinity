package app.infinity.mpvz.ui.theme

import org.junit.Assert.*
import org.junit.Test

class GlassSamplingPolicyTest {
  @Test fun onlyEnabledVisibleSdrVideoMayBeSampled() {
    assertTrue(GlassSamplingPolicy.canSample(true, true, false, false, false, 0))
    assertFalse(GlassSamplingPolicy.canSample(false, true, false, false, false, 0))
    assertFalse(GlassSamplingPolicy.canSample(true, false, false, false, false, 0))
    assertFalse(GlassSamplingPolicy.canSample(true, true, true, false, false, 0))
    assertFalse(GlassSamplingPolicy.canSample(true, true, false, true, false, 0))
    assertFalse(GlassSamplingPolicy.canSample(true, true, false, false, true, 0))
    assertFalse(GlassSamplingPolicy.canSample(true, true, false, false, false, 3))
    assertFalse(GlassSamplingPolicy.canSample(true, true, false, false, false, 6))
  }

  @Test fun samplesAreBoundedAndRetainPortraitLandscapeAspect() {
    assertEquals(192 to 108, GlassSamplingPolicy.sampleSize(3840, 2160))
    assertEquals(108 to 192, GlassSamplingPolicy.sampleSize(2160, 3840))
    assertEquals(96 to 54, GlassSamplingPolicy.sampleSize(96, 54))
    assertEquals(1 to 192, GlassSamplingPolicy.sampleSize(1, 10000))
    assertEquals(192 to 1, GlassSamplingPolicy.sampleSize(Int.MAX_VALUE, 1))
    assertEquals(192 to 192, GlassSamplingPolicy.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
    assertNull(GlassSamplingPolicy.sampleSize(0, 100))
    assertNull(GlassSamplingPolicy.sampleSize(100, -1))
  }

  @Test fun pausedAndFailedSourcesDoNotPollAtPlaybackCadence() {
    // Regression: 200 ms (5 fps) made video behind decoder/subtitle sheets visibly slower.
    assertTrue(GlassSamplingPolicy.PlayingIntervalMs <= 17L)
    assertEquals(16L, GlassSamplingPolicy.interval(true, 0))
    assertEquals(16L, GlassSamplingPolicy.interval(true, 0, thermalStatus = 1))
    assertEquals(33L, GlassSamplingPolicy.interval(true, 0, thermalStatus = 2))
    assertEquals(1000L, GlassSamplingPolicy.interval(false, 0, thermalStatus = 0))
    assertEquals(1000L, GlassSamplingPolicy.interval(false, 0))
    assertEquals(1200L, GlassSamplingPolicy.interval(true, 1))
    assertEquals(1200L, GlassSamplingPolicy.interval(false, 2))
    assertEquals(3, GlassSamplingPolicy.FailureLimit)
  }

  @org.junit.Test fun pacingSubtractsCopyTimeWithoutBusyLooping() {
    assertEquals(10L, GlassSamplingPolicy.remainingDelay(16L, 6L))
    assertEquals(1L, GlassSamplingPolicy.remainingDelay(16L, 40L))
    assertEquals(16L, GlassSamplingPolicy.remainingDelay(16L, -5L))
    assertEquals(0L, GlassSamplingPolicy.remainingDelay(1000L, 2000L))
  }

  @org.junit.Test fun backdropLoopUsesPacedThermalAwareInterval() {
    val source = java.io.File("src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt")
      .takeIf { it.exists() } ?: java.io.File("app/src/main/java/app/infinity/mpvz/ui/theme/LiquidGlassBackdrop.kt")
    val text = source.readText()
    assertTrue(text.contains("GlassSamplingPolicy.interval(playing(), failures, thermalNow)"))
    assertTrue(text.contains("SystemClock.uptimeMillis() - sampleStarted"))
  }
}
