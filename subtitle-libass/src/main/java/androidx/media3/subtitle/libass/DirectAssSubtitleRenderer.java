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
import java.util.ArrayList;
import java.util.List;

/** Reads raw ASS/SSA samples before Media3 decodes them into Cue objects. */
@UnstableApi
public final class DirectAssSubtitleRenderer extends BaseRenderer {
  public interface TrackSink { void replaceTrack(String id, byte[] assDocument); void appendEvent(String id, byte[] event, long timestampUs, long durationUs); void removeTrack(String id); }
  private final TrackSink sink;
  private final DecoderInputBuffer inputBuffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
  private final FormatHolder formatHolder = new FormatHolder();
  private final List<AssEvent> events = new ArrayList<>();
  @Nullable private String trackId;
  @Nullable private byte[] document;
  private boolean inputEnded;

  public DirectAssSubtitleRenderer(TrackSink sink) { super(C.TRACK_TYPE_TEXT); this.sink = sink; }
  @Override public String getName() { return "DirectAssSubtitleRenderer"; }
  @Override public int supportsFormat(Format format) {
    String mime = format.sampleMimeType;
    String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(java.util.Locale.ROOT);
    boolean ass = MimeTypes.TEXT_SSA.equals(mime)
        || "application/ssa".equals(mime)
        || "text/x-ass".equals(mime)
        || "application/x-ass".equals(mime)
        || (mime != null && (mime.contains("ssa") || mime.contains("ass")))
        || codecs.contains("ssa") || codecs.contains("ass");
    int capability = ass ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_SUBTYPE;
    android.util.Log.d("Media3Libass", "supports format mime=" + mime + " codecs=" + format.codecs + " handled=" + ass);
    return RendererCapabilities.create(capability);
  }
  @Override public int supportsMixedMimeTypeAdaptation() { return RendererCapabilities.ADAPTIVE_NOT_SUPPORTED; }
  @Override protected void onStreamChanged(Format[] formats, long startPositionUs, long offsetUs, MediaSource.MediaPeriodId mediaPeriodId) {
    Format format = formats[0];
    trackId = format.id != null ? format.id : "embedded-ass:" + System.identityHashCode(format);
    document = joinInitializationData(format.initializationData);
    inputEnded = false;
    events.clear();
    sink.replaceTrack(trackId, document);
    android.util.Log.i("Media3Libass", "direct ASS stream id=" + trackId + " mime=" + format.sampleMimeType + " headerBytes=" + (document == null ? 0 : document.length));
  }
  @Override protected void onPositionReset(long positionUs, boolean joining) {
    inputBuffer.clear();
    inputEnded = false;
    if (trackId != null && document != null) {
      sink.replaceTrack(trackId, document);
      for (AssEvent event : events) {
        if (event.timestampUs <= positionUs) {
          sink.appendEvent(trackId, event.data, event.timestampUs, event.durationUs);
        }
      }
    }
  }
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
        sample = normalizeEvent(sample);
        if (sample.length == 0) { inputBuffer.clear(); continue; }
        long timestampUs = inputBuffer.timeUs;
        long durationUs = 0L;
        long[] assTimesUs = parseDialogueTimesUs(sample);
        if (assTimesUs != null) {
          if (timestampUs <= 0L) timestampUs = assTimesUs[0];
          durationUs = Math.max(1L, assTimesUs[1] - assTimesUs[0]);
        }
        events.add(new AssEvent(sample, timestampUs, durationUs));
        sink.appendEvent(trackId, sample, timestampUs, durationUs);
      }
      inputBuffer.clear();
    }
  }
  @Override public boolean isReady() { return true; }
  @Override public boolean isEnded() { return inputEnded; }
  @Override protected void onDisabled() { if (trackId != null) sink.removeTrack(trackId); trackId = null; document = null; events.clear(); inputBuffer.clear(); }

  private static final class AssEvent {
    final byte[] data;
    final long timestampUs;
    final long durationUs;
    AssEvent(byte[] data, long timestampUs, long durationUs) {
      this.data = data;
      this.timestampUs = timestampUs;
      this.durationUs = durationUs;
    }
  }
  private static byte[] joinInitializationData(@Nullable java.util.List<byte[]> data) {
    if (data == null || data.isEmpty()) return defaultHeader();
    int total = 0; for (byte[] part : data) total += part.length;
    byte[] result = new byte[total]; int offset = 0;
    for (byte[] part : data) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
    return result;
  }
  /** Matroska stores SSA samples as ReadOrder,Layer,Start,End,... without Dialogue:. */
  private static byte[] normalizeEvent(byte[] sample) {
    String text = new String(sample, StandardCharsets.UTF_8)
        .replace("\u0000", "").replace("\uFEFF", "").trim();
    StringBuilder normalized = new StringBuilder(text.length() + 12);
    for (String line : text.split("\\r?\\n")) {
      String value = line.trim();
      if (value.isEmpty()) continue;
      if (value.regionMatches(true, 0, "Dialogue:", 0, 9)
          || value.regionMatches(true, 0, "Comment:", 0, 8)) {
        String[] fields = value.substring(value.indexOf(':') + 1).trim().split(",", -1);
        if (fields.length >= 4 && isInteger(fields[0].trim())
            && parseAssTimeUs(fields[1].trim()) >= 0 && parseAssTimeUs(fields[2].trim()) >= 0) {
          // Already canonical ASS: Layer,Start,End,Style,... . Preserve it.
          normalized.append("Dialogue: ").append(fields[0]).append(',')
              .append(fields[1]).append(',').append(fields[2]).append(',').append(fields[3]);
          for (int i = 4; i < fields.length; i++) normalized.append(',').append(fields[i]);
        } else if (fields.length >= 5 && parseAssTimeUs(fields[0].trim()) >= 0
            && parseAssTimeUs(fields[1].trim()) >= 0
            && isInteger(fields[2]) && isInteger(fields[3])) {
          normalized.append("Dialogue: ").append(fields[3]).append(',')
              .append(fields[0]).append(',').append(fields[1]).append(',').append(fields[4]);
          for (int i = 5; i < fields.length; i++) normalized.append(',').append(fields[i]);
        } else if (fields.length >= 4 && parseAssTimeUs(fields[0].trim()) >= 0
            && isInteger(fields[1]) && isInteger(fields[2])) {
          normalized.append("Dialogue: 0,").append(fields[0]).append(',')
              .append(addAssDuration(fields[0], 4_000_000L)).append(',').append(fields[3]);
          for (int i = 4; i < fields.length; i++) normalized.append(',').append(fields[i]);
        } else {
          normalized.append(value);
        }
      } else {
        int comma = value.indexOf(',');
        if (comma <= 0 || value.indexOf(',', comma + 1) < 0) continue;
        // Matroska commonly stores ReadOrder,Layer,Start,End,..., but some
        // extractors omit ReadOrder and emit Start,End,.... Only discard the
        // first field when it is an integer; dropping a clock value corrupts
        // the ASS field order and makes libass render nothing.
        String first = value.substring(0, comma).trim();
        boolean readOrder = true;
        try { Integer.parseInt(first); } catch (NumberFormatException ignored) { readOrder = false; }
        String body = readOrder ? value.substring(comma + 1) : value;
        String[] fields = body.split(",", -1);
        if (fields.length >= 3 && parseAssTimeUs(fields[0].trim()) >= 0
            && parseAssTimeUs(fields[1].trim()) >= 0) {
          normalized.append("Dialogue: 0,").append(fields[0]).append(',').append(fields[1])
              .append(',').append(fields[2]);
          for (int i = 3; i < fields.length; i++) normalized.append(',').append(fields[i]);
        } else {
          normalized.append("Dialogue: ").append(body);
        }
      }
      normalized.append('\n');
    }
    return normalized.toString().getBytes(StandardCharsets.UTF_8);
  }
  @Nullable private static long[] parseDialogueTimesUs(byte[] sample) {
    String text = new String(sample, StandardCharsets.UTF_8);
    for (String line : text.split("\\r?\\n")) {
      if (!line.regionMatches(true, 0, "Dialogue:", 0, 9)
          && !line.regionMatches(true, 0, "Comment:", 0, 8)) continue;
      String[] fields = line.substring(line.indexOf(':') + 1).trim().split(",", 11);
      long startUs = -1L;
      for (String field : fields) {
        long parsed = parseAssTimeUs(field.trim());
        if (parsed < 0) continue;
        if (startUs < 0) startUs = parsed;
        else if (parsed > startUs) return new long[] {startUs, parsed};
      }
    }
    return null;
  }

  private static long parseAssTimeUs(String value) {
    String[] parts = value.split(":");
    if (parts.length != 3 && parts.length != 4) return -1L;
    try {
      int hours = Integer.parseInt(parts[0]);
      String[] seconds = parts[2].split("\\.", 2);
      int wholeSeconds = Integer.parseInt(seconds[0]);
      int centiseconds = parts.length == 4 ? Integer.parseInt((parts[3] + "00").substring(0, 2))
          : seconds.length == 2 ? Integer.parseInt((seconds[1] + "00").substring(0, 2)) : 0;
      return ((hours * 3600L + Integer.parseInt(parts[1]) * 60L + wholeSeconds) * 1_000_000L)
          + centiseconds * 10_000L;
    } catch (NumberFormatException ignored) {
      return -1L;
    }
  }

  private static boolean isInteger(String value) {
    if (value.isEmpty()) return false;
    int start = value.charAt(0) == '-' ? 1 : 0;
    if (start == value.length()) return false;
    for (int i = start; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
    return true;
  }

  private static String addAssDuration(String value, long durationUs) {
    long cs = Math.max(0L, (parseAssTimeUs(value) + durationUs) / 10_000L);
    long h = cs / 360_000L; cs %= 360_000L;
    long m = cs / 6_000L; cs %= 6_000L;
    long s = cs / 100L; cs %= 100L;
    return String.format(java.util.Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs);
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
