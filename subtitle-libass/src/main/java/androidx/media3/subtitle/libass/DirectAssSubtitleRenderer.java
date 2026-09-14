package androidx.media3.subtitle.libass;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reads raw ASS/SSA samples before Media3 decodes them into Cue objects. */
@UnstableApi
public final class DirectAssSubtitleRenderer extends BaseRenderer {
  public interface TrackSink { void replaceTrack(String id, byte[] assDocument); void removeTrack(String id); }
  private final TrackSink sink;
  private final DecoderInputBuffer inputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
  private final FormatHolder formatHolder = new FormatHolder();
  @Nullable private String trackId;
  @Nullable private byte[] document;
  private boolean inputEnded;

  public DirectAssSubtitleRenderer(TrackSink sink) { super(C.TRACK_TYPE_TEXT); this.sink = sink; }
  @Override public String getName() { return "DirectAssSubtitleRenderer"; }
  @Override public int supportsFormat(Format format) {
    return MimeTypes.TEXT_SSA.equals(format.sampleMimeType) ? RendererCapabilities.create(C.FORMAT_HANDLED) : RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
  }
  @Override public int supportsMixedMimeTypeAdaptation() { return RendererCapabilities.ADAPTIVE_NOT_SUPPORTED; }
  @Override protected void onStreamChanged(Format[] formats, long startPositionUs, long offsetUs, MediaSource.MediaPeriodId mediaPeriodId) {
    Format format = formats[0];
    trackId = format.id != null ? format.id : "embedded-ass:" + System.identityHashCode(format);
    document = joinInitializationData(format.initializationData);
    inputEnded = false;
    android.util.Log.i("Media3Libass", "direct ASS stream id=" + trackId + " headerBytes=" + (document == null ? 0 : document.length));
  }
  @Override protected void onPositionReset(long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame) { inputBuffer.clear(); inputEnded = false; }
  @Override public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    if (inputEnded || trackId == null) return;
    for (int i = 0; i < 32; i++) {
      int result = readSource(formatHolder, inputBuffer, 0);
      if (result == C.RESULT_NOTHING_READ) return;
      if (result == C.RESULT_FORMAT_READ) continue;
      if (result != C.RESULT_BUFFER_READ) return;
      if (inputBuffer.isEndOfStream()) { inputEnded = true; return; }
      inputBuffer.flip();
      ByteBuffer data = inputBuffer.data;
      if (data != null && data.remaining() > 0) {
        byte[] sample = new byte[data.remaining()]; data.get(sample);
        document = appendEvent(document, sample);
        sink.replaceTrack(trackId, document);
        android.util.Log.i("Media3Libass", "direct ASS sample id=" + trackId + " sampleBytes=" + sample.length + " documentBytes=" + document.length);
      }
      inputBuffer.clear();
    }
  }
  @Override public boolean isReady() { return true; }
  @Override public boolean isEnded() { return inputEnded; }
  @Override protected void onDisabled() { if (trackId != null) sink.removeTrack(trackId); trackId = null; document = null; inputBuffer.clear(); }
  private static byte[] joinInitializationData(@Nullable java.util.List<byte[]> data) {
    if (data == null || data.isEmpty()) return defaultHeader();
    int total = 0; for (byte[] part : data) total += part.length;
    byte[] result = new byte[total]; int offset = 0;
    for (byte[] part : data) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
    return result;
  }
  private static byte[] appendEvent(@Nullable byte[] base, byte[] sample) {
    if (base == null) base = defaultHeader();
    boolean newline = base.length == 0 || base[base.length - 1] != '\n';
    byte[] result = new byte[base.length + sample.length + (newline ? 1 : 0)];
    System.arraycopy(base, 0, result, 0, base.length); int offset = base.length;
    if (newline) result[offset++] = '\n';
    System.arraycopy(sample, 0, result, offset, sample.length); return result;
  }
  private static byte[] defaultHeader() {
    return ("[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Arial,54,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,1,3,1,2,45,45,45,1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n").getBytes(StandardCharsets.UTF_8);
  }
}
