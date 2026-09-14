package androidx.media3.subtitle.libass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import androidx.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Transparent overlay view for a dynamic collection of libass subtitle tracks. */
public final class LibassSubtitleView extends View {
  private static final String TAG = "Media3Libass";
  @Nullable private LibassSubtitleRenderer renderer;
  @Nullable private Bitmap bitmap;
  private long lastPositionUs = -1L;
  private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
  private final AtomicLong renderGeneration = new AtomicLong();
  private final AtomicBoolean renderScheduled = new AtomicBoolean();
  private volatile long pendingPositionUs = -1L;

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
      renderGeneration.incrementAndGet();
      renderer = value;
      bitmap = null;
      invalidate();
      Log.i(TAG, "overlay_renderer_attached=" + (value != null));
    }
  }

  public void setPositionUs(long positionUs) {
    LibassSubtitleRenderer current = renderer;
    if (current == null || positionUs < 0) return;
    pendingPositionUs = positionUs;
    if (!renderScheduled.compareAndSet(false, true)) return;
    long generation = renderGeneration.get();
    renderExecutor.execute(() -> {
      try {
        while (generation == renderGeneration.get() && current == renderer) {
          long framePositionUs = pendingPositionUs;
          pendingPositionUs = -1L;
          if (framePositionUs < 0) break;
          byte[] rgba;
          try {
            rgba = current.render(framePositionUs);
          } catch (IllegalStateException closed) {
            break;
          }
          if (generation != renderGeneration.get() || current != renderer) break;
      int width = current.getWidth();
      int height = current.getHeight();
      int[] argb = rgba == null ? null : new int[width * height];
      if (argb != null) {
        for (int i = 0; i < argb.length; i++) {
          int p = i * 4;
          int r = rgba[p] & 0xff, g = rgba[p + 1] & 0xff, b = rgba[p + 2] & 0xff, a = rgba[p + 3] & 0xff;
          argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
      }
          post(() -> {
        if (generation != renderGeneration.get() || current != renderer) return;
        lastPositionUs = framePositionUs;
        if (argb == null) { bitmap = null; invalidate(); return; }
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
          bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        bitmap.setPixels(argb, 0, width, 0, 0, width, height);
        invalidate();
      });
          if (pendingPositionUs < 0) break;
        }
      } finally {
        renderScheduled.set(false);
        if (pendingPositionUs >= 0 && generation == renderGeneration.get() && current == renderer) {
          setPositionUs(pendingPositionUs);
        }
      }
    });
  }

  public long getLastPositionUs() { return lastPositionUs; }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (bitmap == null || bitmap.isRecycled()) return;
    if (getWidth() <= 0 || getHeight() <= 0) return;
    canvas.drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, getWidth(), getHeight()), null);
  }

  @Override protected void onDetachedFromWindow() {
    renderGeneration.incrementAndGet();
    pendingPositionUs = -1L;
    renderExecutor.shutdownNow();
    super.onDetachedFromWindow();
  }
}
