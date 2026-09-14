package app.infinity.mpvz.ui.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.subtitle.libass.DirectAssSubtitleRenderer
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
    // ExoPlayer assigns one text stream to one text renderer. Multiple instances are required
    // for MPV-style simultaneous sign + dialogue tracks from separate Matroska groups.
    repeat(4) {
      out.add(0, DirectAssSubtitleRenderer(object : DirectAssSubtitleRenderer.TrackSink {
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
    }
  }
}
