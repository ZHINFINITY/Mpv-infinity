package androidx.media3.subtitle.libass;

import androidx.annotation.Nullable;

/** JNI boundary for the libass renderer. */
final class LibassNative {
  static {
    System.loadLibrary("media3_subtitle_libass");
  }

  private LibassNative() {}

  static native long nativeCreate(int storageWidth, int storageHeight, @Nullable String fontsDirectory);
  static native boolean nativeSetSize(long handle, int storageWidth, int storageHeight);
  static native int nativeAddTrack(long handle, byte[] data);
  static native boolean nativeAddFont(long handle, String fontName, byte[] data);
  static native boolean nativeAppendEvent(long handle, int trackId, byte[] data, long timestampUs, long durationUs);
  static native boolean nativeRemoveTrack(long handle, int trackId);
  static native boolean nativeSetTrackEnabled(long handle, int trackId, boolean enabled);
  static native void nativeSetSurface(long handle, android.view.Surface surface);
  static native boolean nativeRenderSurface(long handle, long positionUs);
  static native boolean nativeRenderRgba(long handle, long positionUs, byte[] output);
  static native void nativeRelease(long handle);
}
