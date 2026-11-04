package app.infinity.mpvz.ui.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import androidx.annotation.Nullable;
import androidx.media3.subtitle.libass.LibassSubtitleRenderer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** In-layout transparent subtitle texture surface above Media3 video and below Compose controls. */
public final class LibassSubtitleSurfaceView extends TextureView implements TextureView.SurfaceTextureListener {
  private static final String TAG = "Media3Libass";
  @Nullable private volatile LibassSubtitleRenderer renderer;
  @Nullable private Surface nativeSurface;
  private volatile long positionUs;
  private final Object renderExecutorLock = new Object();
  @Nullable private ScheduledExecutorService renderExecutor;
  private volatile boolean surfaceReady;
  private volatile long lastRenderNs;

  public LibassSubtitleSurfaceView(Context context) { super(context); init(); }
  public LibassSubtitleSurfaceView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

  private void init() {
    setOpaque(false);
    setSurfaceTextureListener(this);
    setFocusable(false);
  }

  private void startRenderLoop() {
    if (!isAttachedToWindow() || !surfaceReady || renderer == null) return;
    synchronized (renderExecutorLock) {
      if (renderExecutor != null && !renderExecutor.isShutdown()) return;
      ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "LibassSubtitleRender");
        thread.setDaemon(true);
        return thread;
      });
      renderExecutor = executor;
      executor.scheduleAtFixedRate(() -> {
        LibassSubtitleRenderer current = renderer;
        long now = System.nanoTime();
        if (surfaceReady && current != null && now - lastRenderNs >= 33_000_000L) {
          lastRenderNs = now;
          try { current.renderSurface(positionUs); } catch (IllegalStateException ignored) { }
        }
      }, 0, 16, TimeUnit.MILLISECONDS);
    }
  }

  private void stopRenderLoop() {
    ScheduledExecutorService executor;
    synchronized (renderExecutorLock) {
      executor = renderExecutor;
      renderExecutor = null;
      if (executor != null) executor.shutdownNow();
    }
    if (executor == null) return;
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        Log.w(TAG, "Subtitle render thread did not stop within 2 seconds");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void detachNativeSurface() {
    LibassSubtitleRenderer current = renderer;
    if (current != null) {
      try { current.setSurface(null); } catch (IllegalStateException ignored) { }
    }
  }

  public void setRenderer(@Nullable LibassSubtitleRenderer value) {
    LibassSubtitleRenderer old = renderer;
    if (old == value && value != null) {
      if (surfaceReady && nativeSurface != null) value.setSurface(nativeSurface);
      startRenderLoop();
      return;
    }
    // Stop new callbacks first, then wait for any in-flight frame before detaching the old surface.
    renderer = null;
    stopRenderLoop();
    if (old != null) {
      try { old.setSurface(null); } catch (IllegalStateException ignored) { }
    }
    renderer = value;
    if (value != null && surfaceReady && nativeSurface != null) value.setSurface(nativeSurface);
    startRenderLoop();
  }

  public void setPositionUs(long value) { positionUs = Math.max(0L, value); }

  @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
    nativeSurface = new Surface(texture);
    surfaceReady = true;
    LibassSubtitleRenderer current = renderer;
    if (current != null) current.setSurface(nativeSurface);
    startRenderLoop();
  }

  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }

  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
    surfaceReady = false;
    stopRenderLoop();
    detachNativeSurface();
    if (nativeSurface != null) { nativeSurface.release(); nativeSurface = null; }
    return true;
  }

  @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (nativeSurface != null && isAvailable()) {
      surfaceReady = true;
      LibassSubtitleRenderer current = renderer;
      if (current != null) current.setSurface(nativeSurface);
      startRenderLoop();
    }
  }

  @Override protected void onDetachedFromWindow() {
    surfaceReady = false;
    stopRenderLoop();
    detachNativeSurface();
    super.onDetachedFromWindow();
  }
}
