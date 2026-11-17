package androidx.media3.subtitle.libass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public final class LibassRendererConcurrencyContractTest {
  @Test
  public void dimensionReadsDoNotWaitForSubtitleRenderingMonitor() throws Exception {
    Method getSize = LibassSubtitleRenderer.class.getDeclaredMethod("getSize");
    Method getWidth = LibassSubtitleRenderer.class.getDeclaredMethod("getWidth");
    Method getHeight = LibassSubtitleRenderer.class.getDeclaredMethod("getHeight");
    Method renderFrame = LibassSubtitleRenderer.class.getDeclaredMethod("renderFrame", long.class);

    assertFalse("getSize must not contend with renderSurface", Modifier.isSynchronized(getSize.getModifiers()));
    assertFalse("getWidth must not contend with renderSurface", Modifier.isSynchronized(getWidth.getModifiers()));
    assertFalse("getHeight must not contend with renderSurface", Modifier.isSynchronized(getHeight.getModifiers()));
    assertTrue("renderFrame must pair pixels and dimensions under the renderer lock", Modifier.isSynchronized(renderFrame.getModifiers()));

    Field size = LibassSubtitleRenderer.class.getDeclaredField("size");
    assertTrue("dimension snapshot must be safely published", Modifier.isVolatile(size.getModifiers()));
  }
}
