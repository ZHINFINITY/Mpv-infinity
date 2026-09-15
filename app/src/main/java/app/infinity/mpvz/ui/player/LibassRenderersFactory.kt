package app.infinity.mpvz.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.subtitle.libass.LibassSubtitleRenderer
import java.util.ArrayList

/** Registers raw ASS renderers before Media3's Cue decoder. */
class LibassRenderersFactory(
  context: Context,
  private val rendererProvider: () -> LibassSubtitleRenderer?,
) : DefaultRenderersFactory(context) {
  override fun buildTextRenderers(
    context: Context,
    output: TextOutput,
    outputLooper: Looper,
    extensionRendererMode: Int,
    out: ArrayList<Renderer>,
  ) {
    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    // Embedded ASS/SSA is extracted by StandaloneAssSubtitleController. Do not register a
    // second direct renderer here: ExoPlayer would deliver the same Matroska streams again,
    // creating duplicate events and bypassing the original ASS timing path.
  }
}
