package app.infinity.mpvz.ui.player;

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
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Silent Media3 text renderer: forwards one raw subtitle sample at a time to libass. */
final class Media3LibassRenderer extends BaseRenderer {
  private final java.util.function.Supplier<LibassSubtitleRenderer> rendererProvider;
  private final FormatHolder formatHolder = new FormatHolder();
  private final DecoderInputBuffer inputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
  private LibassSubtitleRenderer renderer;
  private String trackId;
  private final Map<String, String> formatTrackIds = new LinkedHashMap<>();
  private boolean inputEnded;
  private long streamOffsetUs;

  Media3LibassRenderer(java.util.function.Supplier<LibassSubtitleRenderer> rendererProvider) {
    super(C.TRACK_TYPE_TEXT);
    this.rendererProvider = rendererProvider;
  }
  @Override public String getName() { return "Media3LibassRenderer"; }
  @Override public int supportsFormat(Format format) throws ExoPlaybackException {
    String mime = format.sampleMimeType == null ? "" : format.sampleMimeType.toLowerCase();
    String codecs = format.codecs == null ? "" : format.codecs.toLowerCase();
    boolean ass = mime.contains("ass") || mime.contains("ssa") || codecs.contains("ass") || codecs.contains("ssa");
    return ass ? RendererCapabilities.create(C.FORMAT_HANDLED) : RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
  }
  @Override protected void onStreamChanged(Format[] formats, long startPositionUs, long offsetUs, MediaSource.MediaPeriodId mediaPeriodId) throws ExoPlaybackException {
    if (formats.length == 0) return;
    renderer = rendererProvider.get();
    if (renderer == null) return;
    for (String id : formatTrackIds.values()) renderer.removeTrack(id);
    formatTrackIds.clear();
    streamOffsetUs = offsetUs;
    for (Format format : formats) {
      String key = format.id == null ? format.sampleMimeType + ":" + formatTrackIds.size() : format.id;
      String id = "media3:" + key;
      formatTrackIds.put(key, id);
      byte[] document = join(format.initializationData);
      if (document.length > 0) renderer.addTrack(id, document);
      renderer.setTrackEnabled(id, true);
    }
    trackId = formatTrackIds.values().iterator().next();
    inputEnded = false;
  }
  @Override protected void onPositionReset(long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame) {
    inputEnded = false;
    inputBuffer.clear();
  }
  @Override public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    if (inputEnded || renderer == null || trackId == null) return;
    for (int i = 0; i < 32; i++) {
      inputBuffer.clear();
      int result = readSource(formatHolder, inputBuffer, 0);
      if (result == C.RESULT_NOTHING_READ) return;
      if (result == C.RESULT_FORMAT_READ) {
        Format format = formatHolder.format;
        if (format != null) {
          String key = format.id == null ? format.sampleMimeType : format.id;
          trackId = formatTrackIds.get(key);
        }
        continue;
      }
      if (result != C.RESULT_BUFFER_READ) return;
      if (inputBuffer.isEndOfStream()) { inputEnded = true; return; }
      ByteBuffer data = inputBuffer.data;
      if (data == null || !data.hasRemaining()) continue;
      byte[] sample = new byte[data.remaining()];
      data.get(sample);
      long timestampUs = Math.max(0L, inputBuffer.timeUs - streamOffsetUs);
      renderer.appendEvent(trackId, normalizeRawSample(sample, timestampUs, 4_000_000L), timestampUs, 4_000_000L);
    }
  }
  @Override public boolean isReady() { return renderer != null && !inputEnded; }
  @Override public boolean isEnded() { return inputEnded; }
  @Override protected void onDisabled() {
    if (renderer != null) for (String id : formatTrackIds.values()) renderer.removeTrack(id);
    formatTrackIds.clear(); trackId = null; renderer = null;
  }
  private static byte[] join(java.util.List<byte[]> parts) {
    if (parts == null || parts.isEmpty()) return new byte[0];
    int size = 0; for (byte[] part : parts) size += part.length;
    byte[] result = new byte[size]; int offset = 0;
    for (byte[] part : parts) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
    return result;
  }
  private static byte[] normalizeRawSample(byte[] data, long timestampUs, long durationUs) {
    String text = new String(data, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    if (text.isEmpty()) return data;
    final String prefix = "Dialogue: 0:00:00:00,0:00:00:00,";
    if (text.startsWith(prefix)) {
      String body = text.substring(prefix.length());
      return ("Dialogue: 0," + assTime(timestampUs) + "," + assTime(timestampUs + durationUs) + "," + body).getBytes(StandardCharsets.UTF_8);
    }
    for (String line : text.split("\\r?\\n")) {
      String value = line.trim();
      String body = value.contains(":") ? value.substring(value.indexOf(':') + 1).trim() : value;
      String[] fields = body.split(",", -1);
      if (fields.length >= 3 && looksLikeTime(fields[1]) && looksLikeTime(fields[2])) return value.getBytes(StandardCharsets.UTF_8);
    }
    return ("Dialogue: 0," + assTime(timestampUs) + "," + assTime(timestampUs + durationUs) + ",Default,,0,0,0," + text).getBytes(StandardCharsets.UTF_8);
  }
  private static boolean looksLikeTime(String value) {
    String[] p = value.trim().split(":");
    return p.length == 3 && p[0].matches("\\d+") && p[1].matches("\\d+") && p[2].matches("\\d+(\\.\\d+)?");
  }
  private static String assTime(long us) {
    long cs = Math.max(0L, us / 10_000L); long h = cs / 360_000L; cs %= 360_000L;
    long m = cs / 6_000L; cs %= 6_000L; long s = cs / 100L; cs %= 100L;
    return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs);
  }
}
