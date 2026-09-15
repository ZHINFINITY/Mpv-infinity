package app.infinity.mpvz.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.subtitle.libass.LibassSubtitleRenderer
import java.util.ArrayList

/** Registers the raw ASS renderer before Media3's Cue renderer. */
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
    out.add(Media3LibassRenderer(rendererProvider, positionConsumer))
    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
  }
}
