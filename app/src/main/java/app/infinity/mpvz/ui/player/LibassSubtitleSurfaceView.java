package app.infinity.mpvz.ui.player;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.media3.subtitle.libass.LibassSubtitleRenderer;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Transparent native subtitle surface above Media3's untouched video SurfaceView. */
public final class LibassSubtitleSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
  @Nullable private volatile LibassSubtitleRenderer renderer;
  private volatile long positionUs;
  private final ScheduledExecutorService renderExecutor = Executors.newSingleThreadScheduledExecutor();
  private volatile boolean surfaceReady;

  public LibassSubtitleSurfaceView(Context context) { super(context); init(); }
  public LibassSubtitleSurfaceView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

  private void init() {
    setZOrderMediaOverlay(true);
    getHolder().setFormat(PixelFormat.TRANSLUCENT);
    getHolder().addCallback(this);
    setFocusable(false);
    renderExecutor.scheduleAtFixedRate(() -> {
      LibassSubtitleRenderer current = renderer;
      if (surfaceReady && current != null) {
        try { current.renderSurface(positionUs); } catch (IllegalStateException ignored) { }
      }
    }, 0, 16, TimeUnit.MILLISECONDS);
  }

  public void setRenderer(@Nullable LibassSubtitleRenderer value) {
    LibassSubtitleRenderer old = renderer;
    if (old != null) try { old.setSurface(null); } catch (IllegalStateException ignored) { }
    renderer = value;
    if (value != null && surfaceReady) value.setSurface(getHolder().getSurface());
  }

  public void setPositionUs(long value) { positionUs = Math.max(0L, value); }

  @Override public void surfaceCreated(SurfaceHolder holder) {
    surfaceReady = true;
    LibassSubtitleRenderer current = renderer;
    if (current != null) current.setSurface(holder.getSurface());
  }
  @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
  @Override public void surfaceDestroyed(SurfaceHolder holder) {
    surfaceReady = false;
    LibassSubtitleRenderer current = renderer;
    if (current != null) current.setSurface(null);
  }
  @Override protected void onDetachedFromWindow() {
    surfaceReady = false;
    renderExecutor.shutdownNow();
    super.onDetachedFromWindow();
  }
}
