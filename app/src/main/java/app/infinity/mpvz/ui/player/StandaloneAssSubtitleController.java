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
          renderer.appendEvent(track.id, sample.data, sample.timeUs, sample.durationUs);
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
    if (label == null || label.isEmpty()) return;
    synchronized (enabledLabels) {
      if (!enabledLabels.contains(label)) enabledLabels.add(label);
    }
    synchronized (tracks) {
      for (RawTrack track : tracks) {
        if (track.ass && track.label.equals(label)) renderer.setTrackEnabled(track.id, true);
      }
    }
  }

  public void disableAll() {
    synchronized (enabledLabels) { enabledLabels.clear(); }
    synchronized (tracks) {
      for (RawTrack track : tracks) if (track.ass) renderer.setTrackEnabled(track.id, false);
    }
  }

  @Override public void close() {
    closed = true;
    executor.shutdownNow();
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
      if (!line.regionMatches(true, 0, "Dialogue:", 0, 9)) continue;
      String[] f = line.substring(9).trim().split(",", 11);
      if (f.length < 3) return null;
      long s = parseTime(f[1].trim()), e = parseTime(f[2].trim());
      if (s >= 0 && e > s) return new long[] {s, e};
    }
    return null;
  }
  private static long parseTime(String value) {
    try {
      String[] p = value.split(":"); String[] s = p[2].split("\\.", 2);
      int cs = s.length == 2 ? Integer.parseInt((s[1] + "00").substring(0, 2)) : 0;
      return ((Integer.parseInt(p[0]) * 3600L + Integer.parseInt(p[1]) * 60L + Integer.parseInt(s[0])) * 1000000L) + cs * 10000L;
    } catch (RuntimeException e) { return -1L; }
  }
}
