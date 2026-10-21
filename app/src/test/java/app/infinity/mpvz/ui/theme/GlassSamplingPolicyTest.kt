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
    assertNull(GlassSamplingPolicy.sampleSize(0, 100))
    assertNull(GlassSamplingPolicy.sampleSize(100, -1))
  }

  @Test fun pausedAndFailedSourcesDoNotPollAtPlaybackCadence() {
    assertTrue(GlassSamplingPolicy.PlayingIntervalMs >= 200L)
    assertEquals(200L, GlassSamplingPolicy.interval(true, 0))
    assertEquals(1000L, GlassSamplingPolicy.interval(false, 0))
    assertEquals(1200L, GlassSamplingPolicy.interval(true, 1))
    assertEquals(1200L, GlassSamplingPolicy.interval(false, 2))
    assertEquals(3, GlassSamplingPolicy.FailureLimit)
  }
}
