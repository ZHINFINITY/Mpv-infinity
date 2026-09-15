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
 * <p>ASS/SSA is intentionally not registered as a Media3 text renderer. The native engine reads
 * the original Matroska subtitle packets through StandaloneAssSubtitleController and sends them
 * directly to libass. Media3 remains responsible for audio/video and track metadata only.
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
    // Deliberately empty: do not decode ASS/SSA into Media3 Cues or consume the subtitle stream.
  }
}
