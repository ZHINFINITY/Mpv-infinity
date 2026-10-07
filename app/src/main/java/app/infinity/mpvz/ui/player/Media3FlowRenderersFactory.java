package app.infinity.mpvz.ui.player;

import android.content.Context;
import android.os.Handler;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.DefaultRenderersFactory.ExtensionRendererMode;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.ArrayList;

/** Adds a GPU-sink MediaCodec renderer ahead of the stock renderers for supported SDR video only. */
@UnstableApi
public final class Media3FlowRenderersFactory extends DefaultRenderersFactory {
  private final Media3FlowVideoSink flowSink;

  public Media3FlowRenderersFactory(Context context, Media3FlowVideoSink flowSink) {
    super(context);
    this.flowSink = flowSink;
  }

  @Override
  protected void buildVideoRenderers(
      Context context,
      @ExtensionRendererMode int extensionRendererMode,
      MediaCodecSelector mediaCodecSelector,
      boolean enableDecoderFallback,
      Handler eventHandler,
      VideoRendererEventListener eventListener,
      long allowedVideoJoiningTimeMs,
      ArrayList<Renderer> out) {
    int firstVideoRenderer = out.size();
    super.buildVideoRenderers(
        context,
        extensionRendererMode,
        mediaCodecSelector,
        enableDecoderFallback,
        eventHandler,
        eventListener,
        allowedVideoJoiningTimeMs,
        out);
    for (int i = 0; i < out.size(); i++) {
      if (out.get(i).getTrackType() == C.TRACK_TYPE_VIDEO) {
        firstVideoRenderer = i;
        break;
      }
    }

    MediaCodecVideoRenderer.Builder builder =
        new MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(getCodecAdapterFactory())
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler)
            .setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            .setVideoSink(flowSink);
    out.add(firstVideoRenderer, new FlowMediaCodecVideoRenderer(builder, flowSink));
  }

  private static final class FlowMediaCodecVideoRenderer extends MediaCodecVideoRenderer {
    private final Media3FlowVideoSink flowSink;

    FlowMediaCodecVideoRenderer(Builder builder, Media3FlowVideoSink flowSink) {
      super(builder);
      this.flowSink = flowSink;
    }

    @Override
    protected int supportsFormat(MediaCodecSelector mediaCodecSelector, Format format) {
      if (!flowSink.isPreflightAvailable() || !Media3FlowVideoSink.supportsFormat(format)) {
        return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
      }
      try {
        return super.supportsFormat(mediaCodecSelector, format);
      } catch (Exception error) {
        // A query error must not take the default renderer out of consideration.
        return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
      }
    }
  }
}
