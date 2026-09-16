package app.infinity.mpvz.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.subtitle.libass.LibassSubtitleRenderer
import java.util.ArrayList

/**
 * Renderer factory for the native engine.
 *
 * <p>The ASS renderer is silent: it consumes raw samples and sends them to libass without sending
 * Cues to Media3's TextOutput.
 */
class LibassRenderersFactory(
  context: Context,
  private val rendererProvider: () -> LibassSubtitleRenderer?,
  private val positionConsumer: (Long) -> Unit,
) : DefaultRenderersFactory(context) {
  override fun buildTextRenderers(
    context: Context,
    output: TextOutput,
    outputLooper: Looper,
    extensionRendererMode: Int,
    out: ArrayList<Renderer>,
  ) {
    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    out.add(Media3LibassRenderer(rendererProvider, positionConsumer))
  }
}
