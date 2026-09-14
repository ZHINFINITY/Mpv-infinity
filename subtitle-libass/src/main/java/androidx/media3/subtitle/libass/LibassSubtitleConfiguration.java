package androidx.media3.subtitle.libass;

import androidx.annotation.Nullable;

/** Immutable configuration for an experimental libass subtitle track. */
public final class LibassSubtitleConfiguration {
  public final int videoWidth;
  public final int videoHeight;
  @Nullable public final String fontsDirectory;
  public final boolean preferEmbeddedFonts;

  public LibassSubtitleConfiguration(
      int videoWidth,
      int videoHeight,
      @Nullable String fontsDirectory,
      boolean preferEmbeddedFonts) {
    if (videoWidth <= 0 || videoHeight <= 0) {
      throw new IllegalArgumentException("Video dimensions must be positive");
    }
    this.videoWidth = videoWidth;
    this.videoHeight = videoHeight;
    this.fontsDirectory = fontsDirectory;
    this.preferEmbeddedFonts = preferEmbeddedFonts;
  }
}
