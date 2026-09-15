package app.infinity.mpvz.ui.player;

import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.subtitle.libass.LibassSubtitleRenderer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Extracts embedded ASS/SSA tracks independently of Media3's Cue pipeline.
 * Media3 remains responsible for video/audio; this controller owns subtitle bytes and timing.
 */
public final class StandaloneAssSubtitleController implements AutoCloseable {
  private static final String TAG = "StandaloneAss";
  private final DataSource.Factory dataSourceFactory;
  private final LibassSubtitleRenderer renderer;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final List<RawTrack> tracks = new ArrayList<>();
  private final List<String> enabledLabels = new ArrayList<>();
  private volatile boolean closed;

  public StandaloneAssSubtitleController(DataSource.Factory dataSourceFactory, LibassSubtitleRenderer renderer) {
    this.dataSourceFactory = dataSourceFactory;
    this.renderer = renderer;
  }

  public void load(Uri uri) {
    executor.execute(() -> {
      try {
        extract(uri);
      } catch (Throwable error) {
        Log.e(TAG, "standalone extraction failed uri=" + uri, error);
      }
    });
  }

  private void extract(Uri uri) throws IOException {
    DataSource source = dataSourceFactory.createDataSource();
    long length = source.open(new DataSpec(uri));
    ExtractorInput input = new DefaultExtractorInput(source, 0, length);
    Extractor extractor = new MatroskaExtractor(
        new DefaultSubtitleParserFactory(), MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA);
    extractor.init(new Output());
    PositionHolder holder = new PositionHolder();
    while (!closed) {
      int result = extractor.read(input, holder);
      if (result == Extractor.RESULT_END_OF_INPUT) break;
      if (result == Extractor.RESULT_SEEK) {
        if (holder.position == input.getPosition()) break;
        source.close();
        source = dataSourceFactory.createDataSource();
        source.open(new DataSpec(uri, holder.position, C.LENGTH_UNSET));
        input = new DefaultExtractorInput(source, holder.position, C.LENGTH_UNSET);
      }
    }
    source.close();
    for (RawTrack track : tracks) {
      if (track.ass && track.document != null) {
        renderer.addTrack(track.id, track.document);
        boolean enabled;
        synchronized (enabledLabels) { enabled = enabledLabels.contains(track.label); }
        renderer.setTrackEnabled(track.id, enabled);
        for (Sample sample : track.samples) {
          if (closed) return;
          byte[] event = normalizeEvent(sample.data);
          long[] times = parseTimes(event);
          // The Matroska sample timestamp is the media timeline. ASS Start/End
          // in raw subtitle payloads are commonly local (often 0:00:00:00),
          // so do not replace the container timestamp with parsed ASS Start.
          long timeUs = sample.timeUs;
          long durationUs = times == null ? sample.durationUs : Math.max(1L, times[1] - times[0]);
          Log.i(TAG, "packet track=" + track.id
              + " rawBytes=" + sample.data.length
              + " normalizedBytes=" + event.length
              + " sampleTimeUs=" + sample.timeUs
              + " sampleDurationUs=" + sample.durationUs
              + " parsedStartUs=" + (times == null ? -1L : times[0])
              + " parsedEndUs=" + (times == null ? -1L : times[1])
              + " submitTimeUs=" + timeUs
              + " submitDurationUs=" + durationUs
              + " rawPreview=" + preview(sample.data)
              + " assPreview=" + preview(event));
          try {
            renderer.appendEvent(track.id, event, timeUs, durationUs);
          } catch (IllegalStateException closedRenderer) {
            if (closed) return;
            throw closedRenderer;
          }
        }
        Log.i(TAG, "loaded raw track id=" + track.id + " label=" + track.label
            + " samples=" + track.samples.size());
      }
    }
  }

  public List<String> getTrackIds() {
    List<String> result = new ArrayList<>();
    synchronized (tracks) { for (RawTrack track : tracks) if (track.ass) result.add(track.id); }
    return result;
  }

  public void enableLabel(String label) {
    if (closed || label == null || label.isEmpty()) return;
    synchronized (enabledLabels) {
      if (!enabledLabels.contains(label)) enabledLabels.add(label);
    }
    synchronized (tracks) {
      for (RawTrack track : tracks) {
        if (closed) return;
        if (track.ass && track.label.equals(label)) {
          try { renderer.setTrackEnabled(track.id, true); } catch (IllegalStateException ignored) { return; }
        }
      }
    }
  }

  public void disableAll() {
    if (closed) return;
    synchronized (enabledLabels) { enabledLabels.clear(); }
    synchronized (tracks) {
      for (RawTrack track : tracks) if (track.ass) {
        try { renderer.setTrackEnabled(track.id, false); } catch (IllegalStateException ignored) { return; }
      }
    }
  }

  public boolean isLabelEnabled(String label) {
    synchronized (enabledLabels) { return enabledLabels.contains(label); }
  }

  @Override public void close() {
    closed = true;
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
        Log.w(TAG, "subtitle extraction did not stop before renderer teardown");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private final class Output implements ExtractorOutput {
    @Override public TrackOutput track(int id, int type) {
      return new CapturingTrack(id, type);
    }
    @Override public void endTracks() {}
    @Override public void seekMap(SeekMap seekMap) {}
  }

  private final class CapturingTrack implements TrackOutput {
    private final int id;
    private final int type;
    @Nullable private RawTrack current;
    @Nullable private Format format;
    private byte[] pending = new byte[0];

    CapturingTrack(int id, int type) { this.id = id; this.type = type; }

    @Override public void format(Format value) {
      format = value;
      String mime = value.sampleMimeType == null ? "" : value.sampleMimeType.toLowerCase();
      String codecs = value.codecs == null ? "" : value.codecs.toLowerCase();
      boolean ass = type == C.TRACK_TYPE_TEXT && (mime.contains("ass") || mime.contains("ssa")
          || codecs.contains("ass") || codecs.contains("ssa"));
      if (!ass) return;
      String trackId = value.id == null ? "embedded-ass-" + id : value.id;
      current = new RawTrack(trackId, value.label == null ? trackId : value.label, true);
      if (value.initializationData != null && !value.initializationData.isEmpty()) {
        int size = 0; for (byte[] part : value.initializationData) size += part.length;
        current.document = new byte[size];
        int offset = 0;
        for (byte[] part : value.initializationData) {
          System.arraycopy(part, 0, current.document, offset, part.length); offset += part.length;
        }
      }
      synchronized (tracks) { tracks.add(current); }
    }

    @Override public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart) throws IOException {
      byte[] bytes = new byte[length];
      int read = input.read(bytes, 0, length);
      if (read == C.RESULT_END_OF_INPUT) return allowEndOfInput ? C.RESULT_END_OF_INPUT : 0;
      append(bytes, read);
      return read;
    }
    @Override public void sampleData(ParsableByteArray data, int length) {
      byte[] bytes = new byte[length]; data.readBytes(bytes, 0, length); append(bytes, length);
    }
    @Override public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
      sampleData(data, length);
    }
    @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, @Nullable TrackOutput.CryptoData cryptoData) {
      if (current == null || (flags & C.BUFFER_FLAG_KEY_FRAME) == 0 && size <= 0) return;
      int start = Math.max(0, pending.length - size - offset);
      int end = Math.min(pending.length, start + size);
      if (end > start) {
        byte[] sample = new byte[end - start]; System.arraycopy(pending, start, sample, 0, sample.length);
        long[] times = parseTimes(sample);
        long sampleTime = times == null ? timeUs : times[0];
        long duration = times == null ? 0L : Math.max(1L, times[1] - times[0]);
        current.samples.add(new Sample(sample, sampleTime, duration));
      }
      pending = new byte[0];
    }
    private void append(byte[] bytes, int length) {
      if (current == null || length <= 0) return;
      byte[] joined = new byte[pending.length + length];
      System.arraycopy(pending, 0, joined, 0, pending.length);
      System.arraycopy(bytes, 0, joined, pending.length, length); pending = joined;
    }
  }

  private static final class RawTrack {
    final String id, label; final boolean ass; @Nullable byte[] document; final List<Sample> samples = new ArrayList<>();
    RawTrack(String id, String label, boolean ass) { this.id = id; this.label = label; this.ass = ass; }
  }
  private static final class Sample { final byte[] data; final long timeUs, durationUs; Sample(byte[] d, long t, long du) { data=d; timeUs=t; durationUs=du; } }

  @Nullable private static long[] parseTimes(byte[] sample) {
    String text = new String(sample, java.nio.charset.StandardCharsets.UTF_8);
    for (String line : text.split("\\r?\\n")) {
      if (!line.regionMatches(true, 0, "Dialogue:", 0, 9)
          && !line.regionMatches(true, 0, "Comment:", 0, 8)) continue;
      String[] fields = line.substring(line.indexOf(':') + 1).trim().split(",", 11);
      long start = -1L;
      for (String field : fields) {
        long parsed = parseTime(field.trim());
        if (parsed < 0) continue;
        if (start < 0) start = parsed;
        else if (parsed > start) return new long[] {start, parsed};
      }
    }
    return null;
  }
  private static long parseTime(String value) {
    try {
      String[] p = value.split(":");
      if (p.length != 3 && p.length != 4) return -1L;
      String[] s = p[2].split("\\.", 2);
      int cs = p.length == 4 ? Integer.parseInt((p[3] + "00").substring(0, 2))
          : s.length == 2 ? Integer.parseInt((s[1] + "00").substring(0, 2)) : 0;
      return ((Integer.parseInt(p[0]) * 3600L + Integer.parseInt(p[1]) * 60L + Integer.parseInt(s[0])) * 1000000L) + cs * 10000L;
    } catch (RuntimeException e) { return -1L; }
  }

  /** Matroska stores SSA blocks as ReadOrder,Layer,Start,End,... without the ASS prefix. */
  private static byte[] normalizeEvent(byte[] sample) {
    String text = new String(sample, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\u0000", "").trim();
    StringBuilder normalized = new StringBuilder(text.length() + 12);
    for (String line : text.split("\\r?\\n")) {
      String value = line.trim();
      if (value.isEmpty()) continue;
      if (value.regionMatches(true, 0, "Dialogue:", 0, 9)
          || value.regionMatches(true, 0, "Comment:", 0, 8)) {
        String[] fields = value.substring(value.indexOf(':') + 1).trim().split(",", -1);
        if (fields.length >= 5 && parseTime(fields[0].trim()) >= 0
            && parseTime(fields[1].trim()) >= 0 && isInteger(fields[2]) && isInteger(fields[3])) {
          normalized.append("Dialogue: ").append(fields[3]).append(',')
              .append(fields[0]).append(',').append(fields[1]).append(',').append(fields[4]);
          for (int i = 5; i < fields.length; i++) normalized.append(',').append(fields[i]);
        } else if (fields.length >= 4 && parseTime(fields[0].trim()) >= 0
            && isInteger(fields[1]) && isInteger(fields[2])) {
          normalized.append("Dialogue: 0,").append(fields[0]).append(',')
              .append(addDuration(fields[0], 4_000_000L)).append(',').append(fields[3]);
          for (int i = 4; i < fields.length; i++) normalized.append(',').append(fields[i]);
        } else {
          normalized.append(value);
        }
      } else {
        int comma = value.indexOf(',');
        if (comma <= 0) continue;
        String first = value.substring(0, comma).trim();
        boolean readOrder = true;
        try { Integer.parseInt(first); } catch (NumberFormatException ignored) { readOrder = false; }
        if (readOrder) {
          normalized.append("Dialogue: ").append(value.substring(comma + 1));
        } else {
          String[] fields = value.split(",", -1);
          if (fields.length < 5 || parseTime(fields[0].trim()) < 0 || parseTime(fields[1].trim()) < 0) continue;
          normalized.append("Dialogue: ").append(fields[3]).append(',')
              .append(fields[0]).append(',').append(fields[1]).append(',').append(fields[4]);
          for (int i = 5; i < fields.length; i++) normalized.append(',').append(fields[i]);
        }
      }
      normalized.append('\n');
    }
    return normalized.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String preview(byte[] value) {
    String text = new String(value, java.nio.charset.StandardCharsets.UTF_8)
        .replace("\u0000", "\\0")
        .replace("\r", "\\r")
        .replace("\n", "\\n");
    return text.length() <= 240 ? text : text.substring(0, 240) + "...";
  }

  private static boolean isInteger(String value) {
    if (value.isEmpty()) return false;
    int start = value.charAt(0) == '-' ? 1 : 0;
    if (start == value.length()) return false;
    for (int i = start; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
    return true;
  }

  private static String addDuration(String value, long durationUs) {
    long cs = Math.max(0L, (parseTime(value) + durationUs) / 10_000L);
    long h = cs / 360_000L; cs %= 360_000L;
    long m = cs / 6_000L; cs %= 6_000L;
    long s = cs / 100L; cs %= 100L;
    return String.format(java.util.Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs);
  }
}
