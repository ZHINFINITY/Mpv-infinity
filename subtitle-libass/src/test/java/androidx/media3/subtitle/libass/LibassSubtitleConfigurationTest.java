package androidx.media3.subtitle.libass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LibassSubtitleConfigurationTest {
  @Test
  public void storesConfiguration() {
    LibassSubtitleConfiguration config =
        new LibassSubtitleConfiguration(1920, 1080, "/fonts", true);
    assertEquals(1920, config.videoWidth);
    assertEquals(1080, config.videoHeight);
    assertEquals("/fonts", config.fontsDirectory);
    assertTrue(config.preferEmbeddedFonts);
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsInvalidDimensions() {
    new LibassSubtitleConfiguration(0, 1080, null, false);
  }
}
