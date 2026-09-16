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
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Media3 renderer that forwards raw ASS/SSA data to the app's libass module. */
final class Media3LibassRenderer extends BaseRenderer {
  private final java.util.function.Supplier<LibassSubtitleRenderer> rendererProvider;
  private final Consumer<Long> positionConsumer;
  private final FormatHolder formatHolder = new FormatHolder();
  private final DecoderInputBuffer inputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
  @Nullable private LibassSubtitleRenderer renderer;
  @Nullable private String trackId;
  private final Map<String, String> formatTrackIds = new LinkedHashMap<>();
  private boolean inputEnded;
  private long streamOffsetUs;
  private String pendingTrackId;
  private long pendingTimestampUs = Long.MIN_VALUE;
  private final ByteArrayOutputStream pendingEvent = new ByteArrayOutputStream();

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
    flushPending();
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
    trackId = formats.length == 0 ? null : formatTrackIds.values().iterator().next();
    inputEnded = false;
  }

  @Override protected void onPositionReset(long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame) {
    flushPending();
    inputEnded = false;
    inputBuffer.clear();
  }

  @Override public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    // The overlay is driven by NativeMedia3Engine's player-position clock. Do not
    // forward BaseRenderer's position here: Media3 may include a renderer stream
    // offset, which is not the player timeline and previously produced positions
    // around 1e12 us in the overlay.
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
          if (trackId == null) {
            trackId = "media3:" + key;
            formatTrackIds.put(key, trackId);
            byte[] document = join(format.initializationData);
            if (document.length > 0) renderer.addTrack(trackId, document);
            renderer.setTrackEnabled(trackId, true);
          }
        }
        continue;
      }
      if (result != C.RESULT_BUFFER_READ) return;
      if (inputBuffer.isEndOfStream()) {
        flushPending();
        inputEnded = true;
        return;
      }
      ByteBuffer data = inputBuffer.data;
      if (data == null || !data.hasRemaining()) continue;
      byte[] event = new byte[data.remaining()];
      data.get(event);
      long timestampUs = Math.max(0L, inputBuffer.timeUs - streamOffsetUs);
      long durationUs = 4_000_000L;
      if (pendingTimestampUs != Long.MIN_VALUE
          && (timestampUs != pendingTimestampUs || !trackId.equals(pendingTrackId))) {
        flushPending();
      }
      if (pendingTimestampUs == Long.MIN_VALUE) {
        pendingTimestampUs = timestampUs;
        pendingTrackId = trackId;
      }
      pendingEvent.write(event, 0, event.length);
    }
  }

  @Override public boolean isReady() { return renderer != null && !inputEnded; }
  @Override public boolean isEnded() { return inputEnded; }

  @Override protected void onDisabled() {
    flushPending();
    if (renderer != null) {
      for (String id : formatTrackIds.values()) renderer.removeTrack(id);
    }
    formatTrackIds.clear();
    trackId = null;
    renderer = null;
  }

  private void flushPending() {
    if (renderer == null || pendingTrackId == null || pendingEvent.size() == 0) {
      pendingEvent.reset();
      pendingTrackId = null;
      pendingTimestampUs = Long.MIN_VALUE;
      return;
    }
    byte[] bytes = pendingEvent.toByteArray();
    long timestampUs = pendingTimestampUs;
    renderer.appendEvent(pendingTrackId, normalizeEvent(bytes, timestampUs, 4_000_000L), timestampUs, 4_000_000L);
    pendingEvent.reset();
    pendingTrackId = null;
    pendingTimestampUs = Long.MIN_VALUE;
  }

  private static byte[] join(java.util.List<byte[]> parts) {
    if (parts == null || parts.isEmpty()) return new byte[0];
    int size = 0;
    for (byte[] part : parts) size += part.length;
    byte[] result = new byte[size];
    int offset = 0;
    for (byte[] part : parts) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
    return result;
  }

  private static byte[] normalizeEvent(byte[] data, long timestampUs, long durationUs) {
    String text = new String(data, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    if (text.isEmpty()) return data;
    for (String line : text.split("\\r?\\n")) {
      String value = line.trim();
      int colon = value.indexOf(':');
      String body = colon >= 0 ? value.substring(colon + 1).trim() : value;
      String[] fields = body.split(",", -1);
      if (fields.length >= 3 && looksLikeTime(fields[1]) && looksLikeTime(fields[2])) {
        return value.getBytes(StandardCharsets.UTF_8);
      }
    }
    String body = text;
    int colon = body.indexOf(':');
    if (colon >= 0) body = body.substring(colon + 1).trim();
    String start = assTime(timestampUs);
    String end = assTime(timestampUs + durationUs);
    return ("Dialogue: 0," + start + "," + end + ",Default,,0,0,0," + body)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static boolean looksLikeTime(String value) {
    String[] p = value.trim().split(":");
    return p.length == 3 && p[0].matches("\\d+") && p[1].matches("\\d+")
        && p[2].matches("\\d+(\\.\\d+)?");
  }

  private static String assTime(long us) {
    long cs = Math.max(0L, us / 10_000L);
    long h = cs / 360_000L; cs %= 360_000L;
    long m = cs / 6_000L; cs %= 6_000L;
    long s = cs / 100L; cs %= 100L;
    return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs);
  }
}
