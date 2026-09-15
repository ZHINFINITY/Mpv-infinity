package app.infinity.mpvz.ui.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.subtitle.libass.LibassSubtitleRenderer;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Media3 renderer that forwards raw ASS/SSA data to the app's libass module. */
final class Media3LibassRenderer extends BaseRenderer {
  private final java.util.function.Supplier<LibassSubtitleRenderer> rendererProvider;
  private final Consumer<Long> positionConsumer;
  private final FormatHolder formatHolder = new FormatHolder();
  private final DecoderInputBuffer inputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
  @Nullable private LibassSubtitleRenderer renderer;
  @Nullable private String trackId;
  private boolean inputEnded;

  Media3LibassRenderer(
      java.util.function.Supplier<LibassSubtitleRenderer> rendererProvider,
      Consumer<Long> positionConsumer) {
    super(C.TRACK_TYPE_TEXT);
    this.rendererProvider = rendererProvider;
    this.positionConsumer = positionConsumer;
  }

  @Override public String getName() { return "Media3LibassRenderer"; }

  @Override public int supportsFormat(Format format) throws ExoPlaybackException {
    String mime = format.sampleMimeType == null ? "" : format.sampleMimeType.toLowerCase();
    String codecs = format.codecs == null ? "" : format.codecs.toLowerCase();
    boolean ass = mime.contains("ass") || mime.contains("ssa") || codecs.contains("ass") || codecs.contains("ssa");
    return ass ? RendererCapabilities.create(C.FORMAT_HANDLED)
        : RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
  }

  @Override protected void onStreamChanged(Format[] formats, long startPositionUs, long offsetUs, MediaSource.MediaPeriodId mediaPeriodId) throws ExoPlaybackException {
    if (formats.length == 0) return;
    Format format = formats[0];
    renderer = rendererProvider.get();
    if (renderer == null) return;
    trackId = "media3:" + (format.id == null ? "ass" : format.id);
    byte[] document = join(format.initializationData);
    if (document.length > 0) renderer.addTrack(trackId, document);
    renderer.setTrackEnabled(trackId, true);
    inputEnded = false;
  }

  @Override protected void onPositionReset(long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame) {
    inputEnded = false;
    inputBuffer.clear();
  }

  @Override public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    positionConsumer.accept(positionUs);
    if (inputEnded || renderer == null || trackId == null) return;
    for (int i = 0; i < 32; i++) {
      inputBuffer.clear();
      int result = readSource(formatHolder, inputBuffer, 0);
      if (result == C.RESULT_NOTHING_READ) return;
      if (result == C.RESULT_FORMAT_READ) {
        Format format = formatHolder.format;
        if (format != null) {
          byte[] document = join(format.initializationData);
          if (document.length > 0) renderer.addTrack(trackId, document);
        }
        continue;
      }
      if (result != C.RESULT_BUFFER_READ) return;
      if (inputBuffer.isEndOfStream()) {
        inputEnded = true;
        return;
      }
      ByteBuffer data = inputBuffer.data;
      if (data == null || !data.hasRemaining()) continue;
      byte[] event = new byte[data.remaining()];
      data.get(event);
      renderer.appendEvent(trackId, event, inputBuffer.timeUs, 0L);
    }
  }

  @Override public boolean isReady() { return renderer != null && !inputEnded; }
  @Override public boolean isEnded() { return inputEnded; }

  private static byte[] join(java.util.List<byte[]> parts) {
    if (parts == null || parts.isEmpty()) return new byte[0];
    int size = 0;
    for (byte[] part : parts) size += part.length;
    byte[] result = new byte[size];
    int offset = 0;
    for (byte[] part : parts) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
    return result;
  }
}
