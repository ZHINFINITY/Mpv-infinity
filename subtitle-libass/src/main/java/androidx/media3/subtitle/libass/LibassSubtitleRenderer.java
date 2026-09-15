package androidx.media3.subtitle.libass;

import android.util.Log;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * Libass-backed compositor for any number of simultaneously enabled ASS/SSA tracks.
 *
 * <p>The caller owns the returned RGBA frame and may copy it into a transparent Android bitmap.
 * Track order is insertion order, allowing callers to establish deterministic MPV-style layers.
 */
public final class LibassSubtitleRenderer implements AutoCloseable {
  private static final String TAG = "Media3Libass";
  private final Map<String, Integer> tracks = new LinkedHashMap<>();
  private int width;
  private int height;
  private long nativeHandle;
  private byte[] frame;

  public LibassSubtitleRenderer(int width, int height, @Nullable String fontsDirectory) {
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid renderer dimensions");
    this.width = width;
    this.height = height;
    nativeHandle = LibassNative.nativeCreate(width, height, fontsDirectory);
    if (nativeHandle == 0) throw new IllegalStateException("Unable to initialize native libass");
    frame = new byte[width * height * 4];
    Log.i(TAG, "initialized size=" + width + "x" + height + " native=true");
  }

  public synchronized void setSize(int width, int height) {
    checkOpen();
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid renderer dimensions");
    if (this.width == width && this.height == height) return;
    if (!LibassNative.nativeSetSize(nativeHandle, width, height)) {
      throw new IllegalStateException("Unable to resize native libass renderer");
    }
    this.width = width;
    this.height = height;
    frame = new byte[width * height * 4];
    Log.i(TAG, "resized size=" + width + "x" + height);
  }

  public synchronized boolean addTrack(String id, byte[] assData) {
    checkOpen();
    if (id == null || id.isEmpty() || assData == null || assData.length == 0) {
      throw new IllegalArgumentException("Track id and ASS data are required");
    }
    Integer old = tracks.remove(id);
    if (old != null) LibassNative.nativeRemoveTrack(nativeHandle, old);
    int nativeId = LibassNative.nativeAddTrack(nativeHandle, assData);
    if (nativeId < 0) {
      Log.e(TAG, "track_add_failed id=" + id + " bytes=" + assData.length);
      return false;
    }
    tracks.put(id, nativeId);
    Log.i(TAG, "track_added id=" + id + " nativeId=" + nativeId + " count=" + tracks.size());
    return true;
  }

  public synchronized boolean appendEvent(String id, byte[] eventData, long timestampUs, long durationUs) {
    checkOpen();
    if (id == null || id.isEmpty() || eventData == null || eventData.length == 0) return false;
    Integer nativeId = tracks.get(id);
    eventData = ensureCanonicalAssLayer(eventData);
    boolean appended = nativeId != null
        && LibassNative.nativeAppendEvent(nativeHandle, nativeId, eventData, timestampUs, durationUs);
    if (!appended) Log.e(TAG, "event_append_failed id=" + id + " bytes=" + eventData.length);
    return appended;
  }

  /** Ensures libass receives Dialogue: Layer,Start,End,Style,... rather than Start,End,Style,... . */
  private static byte[] ensureCanonicalAssLayer(byte[] eventData) {
    String text = new String(eventData, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    if (text.isEmpty()) return eventData;
    StringBuilder result = new StringBuilder(text.length() + 3);
    for (String line : text.split("\\r?\\n")) {
      String value = line.trim();
      if (value.regionMatches(true, 0, "Dialogue:", 0, 9)
          || value.regionMatches(true, 0, "Comment:", 0, 8)) {
        int colon = value.indexOf(':');
        String body = value.substring(colon + 1).trim();
        String[] fields = body.split(",", -1);
        boolean canonical = fields.length >= 3 && isInteger(fields[0])
            && looksLikeAssTime(fields[1]) && looksLikeAssTime(fields[2]);
        if (canonical) {
          value = "Dialogue: " + body;
        } else if (fields.length >= 3 && looksLikeAssTime(fields[0]) && looksLikeAssTime(fields[1])) {
          value = "Dialogue: 0," + body;
        }
      }
      if (value.isEmpty()) continue;
      if (result.length() > 0) result.append('\n');
      result.append(value);
    }
    return result.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static boolean looksLikeAssTime(String value) {
    String[] parts = value.trim().split(":");
    return (parts.length == 3 || parts.length == 4)
        && parts[0].matches("\\d+") && parts[1].matches("\\d+")
        && parts[2].matches("\\d+(\\.\\d+)?");
  }

  private static boolean isInteger(String value) {
    String text = value.trim();
    if (text.isEmpty()) return false;
    int start = text.charAt(0) == '-' ? 1 : 0;
    if (start == text.length()) return false;
    for (int i = start; i < text.length(); i++) {
      if (!Character.isDigit(text.charAt(i))) return false;
    }
    return true;
  }

  public synchronized boolean removeTrack(String id) {
    checkOpen();
    Integer nativeId = tracks.remove(id);
    boolean removed = nativeId != null && LibassNative.nativeRemoveTrack(nativeHandle, nativeId);
    Log.i(TAG, "track_removed id=" + id + " removed=" + removed + " count=" + tracks.size());
    return removed;
  }

  public synchronized boolean setTrackEnabled(String id, boolean enabled) {
    checkOpen();
    Integer nativeId = tracks.get(id);
    boolean changed = nativeId != null && LibassNative.nativeSetTrackEnabled(nativeHandle, nativeId, enabled);
    Log.i(TAG, "track_enabled id=" + id + " enabled=" + enabled + " changed=" + changed);
    return changed;
  }

  public synchronized byte[] render(long positionUs) {
    checkOpen();
    boolean rendered = LibassNative.nativeRenderRgba(nativeHandle, positionUs, frame);
    Log.d(TAG, "frame positionUs=" + positionUs + " tracks=" + tracks.size() + " rendered=" + rendered);
    return rendered ? frame : null;
  }

  public synchronized int getWidth() { return width; }
  public synchronized int getHeight() { return height; }
  public synchronized int getTrackCount() { return tracks.size(); }
  public synchronized Map<String, Integer> getTrackIds() { return Collections.unmodifiableMap(new LinkedHashMap<>(tracks)); }

  @Override public synchronized void close() {
    if (nativeHandle != 0) {
      LibassNative.nativeRelease(nativeHandle);
      nativeHandle = 0;
      tracks.clear();
      frame = null;
      Log.i(TAG, "released");
    }
  }

  private void checkOpen() {
    if (nativeHandle == 0) throw new IllegalStateException("Renderer is closed");
  }
}
