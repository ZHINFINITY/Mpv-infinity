package app.infinity.mpvz.ui.player

import android.os.Handler
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Preserve ASS and video renderers, allowing raw bitmap subtitles to use an async decoder. */
internal class NativeSubtitleRenderersFactory(
  private val delegate: RenderersFactory,
) : RenderersFactory by delegate {
  @Suppress("DEPRECATION")
  override fun createRenderers(
    eventHandler: Handler,
    videoRendererEventListener: VideoRendererEventListener,
    audioRendererEventListener: AudioRendererEventListener,
    textRendererOutput: TextOutput,
    metadataRendererOutput: MetadataOutput,
  ): Array<Renderer> = delegate.createRenderers(
    eventHandler,
    videoRendererEventListener,
    audioRendererEventListener,
    textRendererOutput,
    metadataRendererOutput,
  ).also { renderers ->
    // Only PGS/DVB bypass extraction transcoding. Text/ASS still arrive as Media3 cues.
    // In Media3 1.11.1 raw bitmap tracks use DelegatingSubtitleDecoder (SimpleDecoder),
    // avoiding PNG cue deserialization on the shared audio/video playback thread.
    renderers.filterIsInstance<TextRenderer>().forEach {
      it.experimentalSetLegacyDecodingEnabled(true)
    }
  }
}
