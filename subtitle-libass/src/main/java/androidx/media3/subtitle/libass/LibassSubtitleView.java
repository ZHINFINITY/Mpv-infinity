package androidx.media3.subtitle.libass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import androidx.annotation.Nullable;

/** Transparent overlay view for a dynamic collection of libass subtitle tracks. */
public final class LibassSubtitleView extends View {
  private static final String TAG = "Media3Libass";
  @Nullable private LibassSubtitleRenderer renderer;
  @Nullable private Bitmap bitmap;
  private long lastPositionUs = -1L;

  public LibassSubtitleView(Context context) { super(context); init(); }
  public LibassSubtitleView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

  private void init() {
    setBackgroundColor(Color.TRANSPARENT);
    setWillNotDraw(false);
    setFocusable(false);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  public void setRenderer(@Nullable LibassSubtitleRenderer value) {
    if (renderer != value) {
      renderer = value;
      bitmap = null;
      invalidate();
      Log.i(TAG, "overlay_renderer_attached=" + (value != null));
    }
  }

  public void setPositionUs(long positionUs) {
    if (renderer == null || positionUs < 0) return;
    byte[] rgba = renderer.render(positionUs);
    lastPositionUs = positionUs;
    if (rgba == null) { bitmap = null; invalidate(); return; }
    if (bitmap == null || bitmap.getWidth() != renderer.getWidth() || bitmap.getHeight() != renderer.getHeight()) {
      bitmap = Bitmap.createBitmap(renderer.getWidth(), renderer.getHeight(), Bitmap.Config.ARGB_8888);
    }
    int[] argb = new int[renderer.getWidth() * renderer.getHeight()];
    for (int i = 0; i < argb.length; i++) {
      int p = i * 4;
      int r = rgba[p] & 0xff, g = rgba[p + 1] & 0xff, b = rgba[p + 2] & 0xff, a = rgba[p + 3] & 0xff;
      argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
    bitmap.setPixels(argb, 0, renderer.getWidth(), 0, 0, renderer.getWidth(), renderer.getHeight());
    invalidate();
  }

  public long getLastPositionUs() { return lastPositionUs; }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (bitmap == null || bitmap.isRecycled()) return;
    if (getWidth() <= 0 || getHeight() <= 0) return;
    canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, getWidth(), getHeight()), null);
  }
}
