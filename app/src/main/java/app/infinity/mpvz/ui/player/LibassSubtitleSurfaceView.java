package app.infinity.mpvz.ui.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;
import androidx.annotation.Nullable;
import androidx.media3.subtitle.libass.LibassSubtitleRenderer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** In-layout transparent subtitle texture surface above Media3 video and below Compose controls. */
public final class LibassSubtitleSurfaceView extends TextureView implements TextureView.SurfaceTextureListener {
  @Nullable private volatile LibassSubtitleRenderer renderer;
  @Nullable private Surface nativeSurface;
  private volatile long positionUs;
  private final ScheduledExecutorService renderExecutor = Executors.newSingleThreadScheduledExecutor();
  private volatile boolean surfaceReady;
  private volatile long lastRenderNs;

  public LibassSubtitleSurfaceView(Context context) { super(context); init(); }
  public LibassSubtitleSurfaceView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

  private void init() {
    setOpaque(false);
    setSurfaceTextureListener(this);
    setFocusable(false);
    renderExecutor.scheduleAtFixedRate(() -> {
      LibassSubtitleRenderer current = renderer;
      long now = System.nanoTime();
      if (surfaceReady && current != null && now - lastRenderNs >= 33_000_000L) {
        lastRenderNs = now;
        try { current.renderSurface(positionUs); } catch (IllegalStateException ignored) { }
      }
    }, 0, 16, TimeUnit.MILLISECONDS);
  }

  public void setRenderer(@Nullable LibassSubtitleRenderer value) {
    LibassSubtitleRenderer old = renderer;
    if (old != null) try { old.setSurface(null); } catch (IllegalStateException ignored) { }
    renderer = value;
    if (value != null && surfaceReady && nativeSurface != null) value.setSurface(nativeSurface);
  }

  public void setPositionUs(long value) { positionUs = Math.max(0L, value); }

  @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
    nativeSurface = new Surface(texture);
    surfaceReady = true;
    LibassSubtitleRenderer current = renderer;
    if (current != null) current.setSurface(nativeSurface);
  }
  @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
  @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
    surfaceReady = false;
    LibassSubtitleRenderer current = renderer;
    if (current != null) try { current.setSurface(null); } catch (IllegalStateException ignored) { }
    if (nativeSurface != null) { nativeSurface.release(); nativeSurface = null; }
    return true;
  }
  @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
  @Override protected void onDetachedFromWindow() {
    surfaceReady = false;
    renderExecutor.shutdownNow();
    super.onDetachedFromWindow();
  }
}
