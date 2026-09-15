package app.infinity.mpvz.ui.player;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.subtitle.libass.LibassSubtitleRenderer;
import java.util.Collections;

/**
 * Media3 GPU effect that asks libass for the subtitle frame at each video PTS and composites it
 * through Media3's OpenGL overlay pipeline before the video reaches the SurfaceView.
 */
@UnstableApi
final class LibassGlEffect implements GlEffect {
  private final TextureOverlay overlay;

  LibassGlEffect(java.util.function.Supplier<LibassSubtitleRenderer> rendererProvider) {
    overlay = new BitmapOverlay() {
      private Bitmap bitmap;
      private int[] pixels;

      @Override
      public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
        LibassSubtitleRenderer renderer = rendererProvider.get();
        if (renderer == null) {
          return transparentBitmap(1, 1);
        }
        try {
          byte[] rgba = renderer.render(presentationTimeUs);
          int width = renderer.getWidth();
          int height = renderer.getHeight();
          if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            pixels = new int[width * height];
          }
          if (rgba == null) {
            java.util.Arrays.fill(pixels, 0);
          } else {
            for (int i = 0; i < pixels.length; i++) {
              int p = i * 4;
              int r = rgba[p] & 0xff;
              int g = rgba[p + 1] & 0xff;
              int b = rgba[p + 2] & 0xff;
              int a = rgba[p + 3] & 0xff;
              pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
          }
          bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
          return bitmap;
        } catch (IllegalStateException closed) {
          return transparentBitmap(1, 1);
        }
      }

      private Bitmap transparentBitmap(int width, int height) {
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
          bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
          bitmap.eraseColor(0);
        }
        return bitmap;
      }
    };
  }

  @Override
  public BaseGlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    return new OverlayEffect(Collections.singletonList(overlay)).toGlShaderProgram(context, useHdr);
  }
}
EOF
cd /home/ubuntu/work/mpv-infinity && git diff --check && git status --short
