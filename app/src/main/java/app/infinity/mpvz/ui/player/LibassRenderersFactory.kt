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
  private val cueConsumer: (String) -> Unit = {},
) : DefaultRenderersFactory(context) {
  override fun buildTextRenderers(
    context: Context,
    output: TextOutput,
    outputLooper: Looper,
    extensionRendererMode: Int,
    out: ArrayList<Renderer>,
  ) {
    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    // Put the raw renderer first so the dependency's generic ASS renderer does not consume the
    // stream before we can preserve its original bytes and expose cue text to translation.
    out.add(0, Media3LibassRenderer(rendererProvider, positionConsumer, cueConsumer))
  }
}
