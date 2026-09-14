package app.infinity.mpvz.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.subtitle.libass.DirectAssSubtitleRenderer
import androidx.media3.subtitle.libass.LibassSubtitleRenderer
import java.util.ArrayList

/** Registers the raw ASS sample renderer instead of Media3's Cue decoder. */
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
    out.add(DirectAssSubtitleRenderer(object : DirectAssSubtitleRenderer.TrackSink {
      override fun replaceTrack(id: String, assDocument: ByteArray) {
        rendererProvider()?.addTrack(id, assDocument)
      }
      override fun appendEvent(id: String, event: ByteArray, timestampUs: Long, durationUs: Long) {
        rendererProvider()?.appendEvent(id, event, timestampUs, durationUs)
      }
      override fun removeTrack(id: String) {
        rendererProvider()?.removeTrack(id)
      }
    }))
    super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
  }
}
