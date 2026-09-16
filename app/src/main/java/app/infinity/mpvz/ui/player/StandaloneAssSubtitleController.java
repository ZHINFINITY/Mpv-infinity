package app.infinity.mpvz.ui.player;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.subtitle.libass.LibassSubtitleRenderer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Raw subtitle demuxer. It intentionally does not use Media3's MatroskaExtractor or subtitle
 * parser. Media3 receives the same URI separately for video/audio; this class owns subtitle bytes,
 * codec-private ASS data, and container timestamps before Media3 can rewrite S_TEXT/ASS samples.
 */
public final class StandaloneAssSubtitleController implements AutoCloseable {
  private static final String TAG = "StandaloneAss";
  private final DataSource.Factory dataSourceFactory;
  private final LibassSubtitleRenderer renderer;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final List<RawTrack> tracks = new ArrayList<>();
  private final List<String> enabledLabels = new ArrayList<>();
  private volatile boolean closed;

  public StandaloneAssSubtitleController(DataSource.Factory factory, LibassSubtitleRenderer renderer) {
    this.dataSourceFactory = factory;
    this.renderer = renderer;
  }

  public void load(Uri uri) {
    executor.execute(() -> {
      File file = null;
      try {
        file = materialize(uri);
        extract(file);
      } catch (Throwable error) {
        Log.e(TAG, "raw demux failed uri=" + uri, error);
      } finally {
        if (file != null) //noinspection ResultOfMethodCallIgnored
          file.delete();
      }
    });
  }

  private File materialize(Uri uri) throws IOException {
    File file = File.createTempFile("mpv-subtitles-", ".media");
    DataSource source = dataSourceFactory.createDataSource();
    try {
      long length = source.open(new DataSpec(uri));
      try (FileOutputStream out = new FileOutputStream(file)) {
        byte[] buffer = new byte[256 * 1024];
        int read;
        while (!closed && (read = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
          if (read > 0) out.write(buffer, 0, read);
        }
      }
      Log.i(TAG, "raw_demux_materialized uri=" + uri + " declaredLength=" + length + " bytes=" + file.length());
      return file;
    } finally { source.close(); }
  }

  private void extract(File file) throws IOException {
    MediaExtractor extractor = new MediaExtractor();
    extractor.setDataSource(file.getAbsolutePath());
    try {
      for (int i = 0; i < extractor.getTrackCount() && !closed; i++) {
        MediaFormat format = extractor.getTrackFormat(i);
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (mime == null || !(mime.contains("ssa") || mime.contains("ass"))) continue;
        String id = "raw-ass:" + i;
        String label = format.containsKey(MediaFormat.KEY_TITLE) ? format.getString(MediaFormat.KEY_TITLE) : id;
        byte[] document = codecConfig(format);
        RawTrack track = new RawTrack(id, label == null ? id : label, i, document);
        synchronized (tracks) { tracks.add(track); }
        renderer.addTrack(id, document);
        synchronized (enabledLabels) { renderer.setTrackEnabled(id, enabledLabels.contains(track.label)); }
        extractor.selectTrack(i);
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        while (!closed) {
          int size = extractor.readSampleData(buffer, 0);
          long timeUs = extractor.getSampleTime();
          if (size < 0 || timeUs < 0) break;
          byte[] sample = new byte[size];
          buffer.position(0); buffer.get(sample, 0, size);
          long durationUs = 4_000_000L;
          track.samples++;
          renderer.appendEvent(id, sample, timeUs, durationUs);
          extractor.advance();
          buffer.clear();
        }
        extractor.unselectTrack(i);
        Log.i(TAG, "raw_track_loaded id=" + id + " label=" + track.label
            + " samples=" + track.samples + " codecPrivateBytes=" + document.length);
      }
    } finally { extractor.release(); }
    Log.i(TAG, "raw_demux_complete tracks=" + tracks.size());
  }

  private static byte[] codecConfig(MediaFormat format) {
    for (String key : new String[] {"csd-0", "csd-1"}) {
      if (format.containsKey(key)) {
        ByteBuffer value = format.getByteBuffer(key);
        if (value != null) {
          ByteBuffer copy = value.duplicate(); byte[] bytes = new byte[copy.remaining()]; copy.get(bytes); return bytes;
        }
      }
    }
    return new byte[0];
  }

  public List<String> getTrackIds() {
    List<String> result = new ArrayList<>();
    synchronized (tracks) { for (RawTrack track : tracks) result.add(track.id); }
    return result;
  }
  public void enableLabel(String label) {
    if (label == null) return;
    synchronized (enabledLabels) { if (!enabledLabels.contains(label)) enabledLabels.add(label); }
    synchronized (tracks) { for (RawTrack track : tracks) if (track.label.equals(label)) renderer.setTrackEnabled(track.id, true); }
  }
  public void disableAll() {
    synchronized (enabledLabels) { enabledLabels.clear(); }
    synchronized (tracks) { for (RawTrack track : tracks) renderer.setTrackEnabled(track.id, false); }
  }
  public boolean isLabelEnabled(String label) { synchronized (enabledLabels) { return enabledLabels.contains(label); } }
  @Override public void close() { closed = true; executor.shutdownNow(); }

  private static final class RawTrack {
    final String id, label; final int index; final byte[] document; int samples;
    RawTrack(String id, String label, int index, byte[] document) { this.id = id; this.label = label; this.index = index; this.document = document; }
  }
}
