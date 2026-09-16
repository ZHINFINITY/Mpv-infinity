#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>
#if MEDIA3_LIBASS_HAS_NATIVE
#include <ass/ass.h>
#endif

namespace {
constexpr char kTag[] = "Media3Libass";
struct Track {
  int id;
  bool enabled = true;
#if MEDIA3_LIBASS_HAS_NATIVE
  ASS_Track* ass = nullptr;
#endif
};
struct Renderer {
  std::mutex mutex;
  int width;
  int height;
  std::string fontsDirectory;
  int nextId = 1;
  std::vector<Track> tracks;
  ANativeWindow* window = nullptr;
#if MEDIA3_LIBASS_HAS_NATIVE
  ASS_Library* library = nullptr;
  ASS_Renderer* renderer = nullptr;
#endif
};
Renderer* fromHandle(jlong handle) { return reinterpret_cast<Renderer*>(handle); }

#if MEDIA3_LIBASS_HAS_NATIVE
long long parseAssTimeMs(std::string_view value);
std::string assColor(int argb) {
  const unsigned alpha = 255u - ((static_cast<unsigned>(argb) >> 24) & 0xffu);
  const unsigned red = (static_cast<unsigned>(argb) >> 16) & 0xffu;
  const unsigned green = (static_cast<unsigned>(argb) >> 8) & 0xffu;
  const unsigned blue = static_cast<unsigned>(argb) & 0xffu;
  char value[16];
  std::snprintf(value, sizeof(value), "&H%02X%02X%02X%02X", alpha, blue, green, red);
  return value;
}
void blendImage(const ASS_Image* image, int width, int height, uint8_t* out) {
  for (const ASS_Image* img = image; img != nullptr; img = img->next) {
    uint32_t color = img->color;
    uint8_t r = (color >> 24) & 0xff;
    uint8_t g = (color >> 16) & 0xff;
    uint8_t b = (color >> 8) & 0xff;
    uint8_t colorAlpha = 255 - (color & 0xff);
    for (int y = 0; y < img->h; ++y) {
      int dstY = img->dst_y + y;
      if (dstY < 0 || dstY >= height) continue;
      for (int x = 0; x < img->w; ++x) {
        int dstX = img->dst_x + x;
        if (dstX < 0 || dstX >= width) continue;
        uint8_t coverage = img->bitmap[y * img->stride + x];
        uint8_t alpha = static_cast<uint8_t>((static_cast<unsigned>(coverage) * colorAlpha) / 255u);
        if (alpha == 0) continue;
        uint8_t* px = out + (dstY * width + dstX) * 4;
        unsigned inv = 255u - alpha;
        px[0] = static_cast<uint8_t>((r * alpha + px[0] * inv) / 255u);
        px[1] = static_cast<uint8_t>((g * alpha + px[1] * inv) / 255u);
        px[2] = static_cast<uint8_t>((b * alpha + px[2] * inv) / 255u);
        px[3] = static_cast<uint8_t>(alpha + (px[3] * inv) / 255u);
      }
    }
  }
}

std::string normalizeEvent(std::string_view input, long long timestampUs, long long durationUs) {
  std::string text(input);
  while (!text.empty() && (text.back() == '\0' || text.back() == '\r'
      || text.back() == '\n' || text.back() == ' ' || text.back() == '\t')) {
    text.pop_back();
  }
  while (!text.empty() && (text.front() == '\r' || text.front() == '\n'
      || text.front() == ' ' || text.front() == '\t')) {
    text.erase(text.begin());
  }
  // Strip a UTF-8 BOM if the extractor retained it at the beginning of the sample.
  if (text.size() >= 3 && static_cast<unsigned char>(text[0]) == 0xef
      && static_cast<unsigned char>(text[1]) == 0xbb
      && static_cast<unsigned char>(text[2]) == 0xbf) {
    text.erase(0, 3);
  }
  if (text.empty()) return {};
  auto hasPrefix = [&text](const char* prefix) {
    const size_t length = std::strlen(prefix);
    if (text.size() < length) return false;
    for (size_t i = 0; i < length; ++i) {
      char actual = text[i];
      char expected = prefix[i];
      if (actual >= 'A' && actual <= 'Z') actual = static_cast<char>(actual + ('a' - 'A'));
      if (actual != expected) return false;
    }
    return true;
  };
  if (hasPrefix("Dialogue:") || hasPrefix("Comment:")) {
    const size_t colon = text.find(':');
    std::string body = text.substr(colon + 1);
    while (!body.empty() && (body.front() == ' ' || body.front() == '\t')) body.erase(body.begin());
    // Java-side extractors already emit canonical ASS records. Preserve the
    // Layer field verbatim; in particular, do not mistake Layer=0/1 for
    // Matroska ReadOrder and strip it from the packet.
    const size_t firstComma = body.find(',');
    const size_t secondComma = firstComma == std::string::npos
        ? std::string::npos : body.find(',', firstComma + 1);
    const size_t thirdComma = secondComma == std::string::npos
        ? std::string::npos : body.find(',', secondComma + 1);
    auto isDigits = [](const std::string& value) {
      if (value.empty()) return false;
      for (char c : value) if (c < '0' || c > '9') return false;
      return true;
    };
    if (firstComma > 0 && secondComma != std::string::npos && thirdComma != std::string::npos) {
      const std::string layer = body.substr(0, firstComma);
      const std::string startTime = body.substr(firstComma + 1, secondComma - firstComma - 1);
      const std::string endTime = body.substr(secondComma + 1, thirdComma - secondComma - 1);
      if (isDigits(layer) && parseAssTimeMs(startTime) >= 0 && parseAssTimeMs(endTime) >= 0) {
        return text + '\n';
      }
    }
    std::vector<std::string> fields;
    size_t start = 0;
    while (start <= body.size()) {
      const size_t comma = body.find(',', start);
      fields.emplace_back(body.substr(start, comma == std::string::npos ? body.size() - start : comma - start));
      if (comma == std::string::npos) break;
      start = comma + 1;
    }
    auto isInteger = [](const std::string& value) {
      if (value.empty()) return false;
      size_t i = value[0] == '-' ? 1 : 0;
      if (i == value.size()) return false;
      for (; i < value.size(); ++i) if (value[i] < '0' || value[i] > '9') return false;
      return true;
    };
    const bool firstIsTime = parseAssTimeMs(fields[0]) >= 0;
    const bool secondIsTime = fields.size() > 1 && parseAssTimeMs(fields[1]) >= 0;
    if (!firstIsTime && fields.size() >= 4 && isInteger(fields[0])
        && secondIsTime && parseAssTimeMs(fields[2]) >= 0) {
      // Already canonical ASS: Layer,Start,End,Style,... . Do not apply
      // the Matroska ReadOrder removal below; Layer is required by libass.
      return text + '\n';
    }
    if (firstIsTime && secondIsTime && fields.size() >= 5
        && isInteger(fields[2]) && isInteger(fields[3])) {
      // Start,End,ReadOrder,Layer,Style,... -> Layer,Start,End,Style,...
      std::string normalized = "Dialogue: " + fields[3] + "," + fields[0] + "," + fields[1] + "," + fields[4];
      for (size_t i = 5; i < fields.size(); ++i) normalized += "," + fields[i];
      return normalized + '\n';
    }
    if (firstIsTime && secondIsTime && fields.size() >= 4) {
      // Some Media3 direct samples arrive as Start,End,Style,... after the
      // Java renderer has already removed Matroska's ReadOrder/Layer fields.
      // Restore the mandatory ASS Layer field without changing the payload.
      std::string normalized = "Dialogue: 0," + fields[0] + "," + fields[1] + "," + fields[2];
      for (size_t i = 3; i < fields.size(); ++i) normalized += "," + fields[i];
      return normalized + '\n';
    }
    if (firstIsTime && fields.size() >= 4 && isInteger(fields[1]) && isInteger(fields[2])) {
      // Start,ReadOrder,Layer,Style,... has no end in the packet. The
      // Media3 sample clock supplies the authoritative interval.
      const long long startUs = timestampUs > 0 ? timestampUs : parseAssTimeMs(fields[0]) * 1000;
      const long long endUs = startUs + std::max<long long>(durationUs, 4000000LL);
      auto assClock = [](long long us) {
        long long cs = std::max<long long>(0, us / 10000);
        long long h = cs / 360000; cs %= 360000;
        long long m = cs / 6000; cs %= 6000;
        long long s = cs / 100; cs %= 100;
        char buffer[32]; std::snprintf(buffer, sizeof(buffer), "%lld:%02lld:%02lld.%02lld", h, m, s, cs);
        return std::string(buffer);
      };
      std::string normalized = "Dialogue: 0," + assClock(startUs) + "," + assClock(endUs) + "," + fields[3];
      for (size_t i = 4; i < fields.size(); ++i) normalized += "," + fields[i];
      return normalized + '\n';
    }
    return text + '\n';
  }
  const size_t firstComma = text.find(',');
  if (firstComma == std::string::npos || firstComma == 0
      || text.find(',', firstComma + 1) == std::string::npos) return {};
  // Matroska SSA packets are ReadOrder,Layer,Start,End,... . Drop ReadOrder only.
  return std::string("Dialogue: ") + text.substr(firstComma + 1) + '\n';
}

long long parseAssTimeMs(std::string_view value) {
  const size_t first = value.find(':');
  const size_t second = first == std::string_view::npos ? std::string_view::npos : value.find(':', first + 1);
  if (first == std::string_view::npos || second == std::string_view::npos) return -1;
  const size_t dot = value.find('.', second + 1);
  const size_t thirdColon = value.find(':', second + 1);
  try {
    const long long hours = std::stoll(std::string(value.substr(0, first)));
    const long long minutes = std::stoll(std::string(value.substr(first + 1, second - first - 1)));
    const long long seconds = std::stoll(std::string(value.substr(second + 1,
        thirdColon != std::string_view::npos ? thirdColon - second - 1
        : (dot == std::string_view::npos ? value.size() : dot) - second - 1)));
    long long centiseconds = 0;
    if (thirdColon != std::string_view::npos || dot != std::string_view::npos) {
      const size_t fractionStart = thirdColon != std::string_view::npos ? thirdColon + 1 : dot + 1;
      std::string fraction(value.substr(fractionStart));
      if (fraction.size() > 2) fraction.resize(2);
      while (fraction.size() < 2) fraction.push_back('0');
      centiseconds = std::stoll(fraction);
    }
    return ((hours * 3600 + minutes * 60 + seconds) * 1000) + centiseconds * 10;
  } catch (...) {
    return -1;
  }
}

long long deriveEventDurationMs(std::string_view event) {
  long long start = -1;
  // Do not rely on a particular SSA field count. Matroska packets in the wild
  // occur both with and without ReadOrder/Layer fields, and some contain extra
  // commas before the timing pair. Scan comma-delimited fields for two valid
  // ASS clock values instead.
  size_t fieldStart = 0;
  while (fieldStart < event.size()) {
    const size_t comma = event.find(',', fieldStart);
    const size_t fieldEnd = comma == std::string_view::npos ? event.size() : comma;
    const long long parsed = parseAssTimeMs(event.substr(fieldStart, fieldEnd - fieldStart));
    if (parsed >= 0) {
      if (start < 0) start = parsed;
      else if (parsed > start) return parsed - start;
    }
    if (comma == std::string_view::npos) break;
    fieldStart = comma + 1;
  }
  return 0;
}

std::string assClockFromUs(long long us) {
  long long centiseconds = std::max<long long>(0, us / 10000);
  long long hours = centiseconds / 360000;
  centiseconds %= 360000;
  long long minutes = centiseconds / 6000;
  centiseconds %= 6000;
  long long seconds = centiseconds / 100;
  centiseconds %= 100;
  char buffer[32];
  std::snprintf(buffer, sizeof(buffer), "%lld:%02lld:%02lld.%02lld",
      hours, minutes, seconds, centiseconds);
  return buffer;
}

// Matroska ASS samples commonly contain local 0:00:00 timestamps while the
// extractor supplies the absolute media timestamp separately. Preserve the
// full ASS payload and rewrite only its Start/End fields.
std::string retimeCanonicalEvent(std::string event, long long timestampUs, long long durationUs) {
  if (timestampUs < 0 || durationUs <= 0 || event.rfind("Dialogue: ", 0) != 0) return event;
  const size_t bodyStart = 10;
  const size_t firstComma = event.find(',', bodyStart);
  const size_t secondComma = firstComma == std::string::npos
      ? std::string::npos : event.find(',', firstComma + 1);
  const size_t thirdComma = secondComma == std::string::npos
      ? std::string::npos : event.find(',', secondComma + 1);
  if (firstComma == std::string::npos || secondComma == std::string::npos
      || thirdComma == std::string::npos
      // Canonical ASS is Dialogue: Layer,Start,End,Style,... . The first
      // field is numeric Layer, not a timestamp.
      || parseAssTimeMs(std::string_view(event).substr(firstComma + 1, secondComma - firstComma - 1)) < 0
      || parseAssTimeMs(std::string_view(event).substr(secondComma + 1, thirdComma - secondComma - 1)) < 0) {
    return event;
  }
  return event.substr(0, firstComma + 1) + assClockFromUs(timestampUs) + ","
      + assClockFromUs(timestampUs + durationUs) + event.substr(thirdComma);
}
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeCreate(JNIEnv* env, jclass, jint width, jint height, jstring fonts) {
  auto* state = new Renderer();
  state->width = width;
  state->height = height;
  if (fonts) {
    const char* chars = env->GetStringUTFChars(fonts, nullptr);
    if (chars) state->fontsDirectory = chars;
    env->ReleaseStringUTFChars(fonts, chars);
  }
#if MEDIA3_LIBASS_HAS_NATIVE
  state->library = ass_library_init();
  state->renderer = state->library ? ass_renderer_init(state->library) : nullptr;
  if (!state->renderer) {
    if (state->library) ass_library_done(state->library);
    delete state;
    return 0;
  }
  ass_set_frame_size(state->renderer, width, height);
  ass_set_storage_size(state->renderer, width, height);
  ass_set_fonts(state->renderer, nullptr, "sans-serif", 1, state->fontsDirectory.c_str(), 1);
#else
  __android_log_print(ANDROID_LOG_WARN, kTag, "libass native dependency is not linked");
#endif
  return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeSetSize(JNIEnv*, jclass, jlong handle, jint width, jint height) {
  auto* state = fromHandle(handle);
  if (!state || width <= 0 || height <= 0) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
  state->width = width;
  state->height = height;
#if MEDIA3_LIBASS_HAS_NATIVE
  ass_set_frame_size(state->renderer, width, height);
  ass_set_storage_size(state->renderer, width, height);
#endif
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeSetStyle(JNIEnv* env, jclass, jlong handle,
    jstring fontName, jint fontSize, jint primaryColor, jint outlineColor, jint backgroundColor,
    jint borderSize, jboolean bold, jboolean italic) {
  auto* state = fromHandle(handle);
  if (!state || !fontName || fontSize <= 0 || borderSize < 0) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
#if MEDIA3_LIBASS_HAS_NATIVE
  const char* fontChars = env->GetStringUTFChars(fontName, nullptr);
  if (!fontChars) return JNI_FALSE;
  std::string font(fontChars);
  env->ReleaseStringUTFChars(fontName, fontChars);
  std::vector<std::string> values = {
      "Default.FontName=" + font,
      "Default.FontSize=" + std::to_string(fontSize),
      "Default.PrimaryColour=" + assColor(primaryColor),
      "Default.OutlineColour=" + assColor(outlineColor),
      "Default.BackColour=" + assColor(backgroundColor),
      "Default.BorderStyle=1",
      "Default.Outline=" + std::to_string(borderSize),
      "Default.Bold=" + std::to_string(bold == JNI_TRUE ? 1 : 0),
      "Default.Italic=" + std::to_string(italic == JNI_TRUE ? 1 : 0),
  };
  std::vector<const char*> pointers;
  pointers.reserve(values.size() + 1);
  for (auto& value : values) pointers.push_back(value.c_str());
  pointers.push_back(nullptr);
  ass_set_style_overrides(state->library, pointers.data());
  return JNI_TRUE;
#else
  (void)env; (void)fontName; (void)fontSize; (void)primaryColor; (void)outlineColor;
  (void)backgroundColor; (void)borderSize; (void)bold; (void)italic;
  return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeAddTrack(JNIEnv* env, jclass, jlong handle, jbyteArray data) {
  auto* state = fromHandle(handle);
  if (!state || !data) return -1;
  std::lock_guard lock(state->mutex);
  Track track;
  track.id = state->nextId++;
#if MEDIA3_LIBASS_HAS_NATIVE
  jsize size = env->GetArrayLength(data);
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (!bytes) return -1;
  track.ass = ass_read_memory(state->library, reinterpret_cast<char*>(bytes), size, "utf-8");
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  if (!track.ass) return -1;
#else
  (void)env;
#endif
  int id = track.id;
  state->tracks.push_back(std::move(track));
  return id;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeAddFont(JNIEnv* env, jclass, jlong handle, jstring name, jbyteArray data) {
  auto* state = fromHandle(handle);
  if (!state || !name || !data) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
#if MEDIA3_LIBASS_HAS_NATIVE
  const char* fontName = env->GetStringUTFChars(name, nullptr);
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (!fontName || !bytes) {
    if (fontName) env->ReleaseStringUTFChars(name, fontName);
    if (bytes) env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return JNI_FALSE;
  }
  jsize size = env->GetArrayLength(data);
  ass_add_font(state->library, fontName, reinterpret_cast<char*>(bytes), size);
  env->ReleaseStringUTFChars(name, fontName);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  return JNI_TRUE;
#else
  (void)env;
  return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeAppendEvent(JNIEnv* env, jclass, jlong handle, jint id, jbyteArray data, jlong timestampUs, jlong durationUs) {
  auto* state = fromHandle(handle);
  if (!state || !data) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
  auto it = std::find_if(state->tracks.begin(), state->tracks.end(), [id](const Track& t) { return t.id == id; });
  if (it == state->tracks.end()) return JNI_FALSE;
#if MEDIA3_LIBASS_HAS_NATIVE
  jsize size = env->GetArrayLength(data);
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (!bytes) return JNI_FALSE;
  // The Java silent renderer has already converted Media3's Matroska framing into one
  // canonical ASS event. Preserve its text, tags, layer, style, and coordinates byte-for-byte.
  std::string event(reinterpret_cast<char*>(bytes), size);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
  if (event.empty()) {
    __android_log_print(ANDROID_LOG_WARN, kTag,
        "ignored malformed ASS event track=%d bytes=%d", id, size);
    return JNI_FALSE;
  }
  long long eventDurationMs = static_cast<long long>(durationUs / 1000);
  if (eventDurationMs <= 0) eventDurationMs = deriveEventDurationMs(event);
  // A zero-length chunk is immediately expired by libass. Preserve a packet
  // even when its container timing was unavailable; the next render pass will
  // still use the ASS event's original style, position, and text.
  if (eventDurationMs <= 0) eventDurationMs = 4000;
  ass_process_chunk(it->ass, event.data(), event.size(),
      static_cast<long long>(timestampUs / 1000), eventDurationMs);
  __android_log_print(ANDROID_LOG_DEBUG, kTag,
      "event track=%d bytes=%zu timeUs=%lld durationUs=%lld", id, event.size(),
      static_cast<long long>(timestampUs), eventDurationMs * 1000);
  const size_t previewLength = std::min<size_t>(event.size(), 220);
  __android_log_print(ANDROID_LOG_INFO, kTag, "event_payload track=%d %.*s",
      id, static_cast<int>(previewLength), event.data());
  return JNI_TRUE;
#else
  (void)env;
  return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeRemoveTrack(JNIEnv*, jclass, jlong handle, jint id) {
  auto* state = fromHandle(handle);
  if (!state) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
  auto it = std::find_if(state->tracks.begin(), state->tracks.end(), [id](const Track& t) { return t.id == id; });
  if (it == state->tracks.end()) return JNI_FALSE;
#if MEDIA3_LIBASS_HAS_NATIVE
  if (it->ass) ass_free_track(it->ass);
#endif
  state->tracks.erase(it);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeSetTrackEnabled(JNIEnv*, jclass, jlong handle, jint id, jboolean enabled) {
  auto* state = fromHandle(handle);
  if (!state) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
  for (auto& track : state->tracks) if (track.id == id) { track.enabled = enabled; return JNI_TRUE; }
  return JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeSetSurface(JNIEnv* env, jclass, jlong handle, jobject surface) {
  auto* state = fromHandle(handle);
  if (!state) return;
  std::lock_guard lock(state->mutex);
  if (state->window) { ANativeWindow_release(state->window); state->window = nullptr; }
  if (surface) {
    state->window = ANativeWindow_fromSurface(env, surface);
    if (state->window) ANativeWindow_setBuffersGeometry(state->window, state->width, state->height, WINDOW_FORMAT_RGBA_8888);
  }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeRenderSurface(JNIEnv*, jclass, jlong handle, jlong positionUs) {
  auto* state = fromHandle(handle);
  if (!state) return JNI_FALSE;
  std::unique_lock lock(state->mutex, std::try_to_lock);
  if (!lock.owns_lock()) return JNI_FALSE;
  if (!state->window) return JNI_FALSE;
  ANativeWindow_Buffer buffer{};
  if (ANativeWindow_lock(state->window, &buffer, nullptr) != 0) return JNI_FALSE;
  std::vector<uint8_t> rgba(static_cast<size_t>(state->width) * state->height * 4, 0);
#if MEDIA3_LIBASS_HAS_NATIVE
  for (const auto& track : state->tracks) if (track.enabled && track.ass) {
    int changed = 0;
    ASS_Image* image = ass_render_frame(state->renderer, track.ass, positionUs / 1000, &changed);
    blendImage(image, state->width, state->height, rgba.data());
  }
#endif
  const int copyHeight = std::min(state->height, buffer.height);
  const int copyWidth = std::min(state->width, buffer.width);
  for (int y = 0; y < copyHeight; ++y) {
    std::memcpy(static_cast<uint8_t*>(buffer.bits) + static_cast<size_t>(y) * buffer.stride * 4,
        rgba.data() + static_cast<size_t>(y) * state->width * 4, static_cast<size_t>(copyWidth) * 4);
  }
  ANativeWindow_unlockAndPost(state->window);
  return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeRenderRgba(JNIEnv* env, jclass, jlong handle, jlong positionUs, jbyteArray output) {
  auto* state = fromHandle(handle);
  if (!state || !output) return JNI_FALSE;
  const jsize expected = state->width * state->height * 4;
  if (env->GetArrayLength(output) < expected) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
  std::vector<uint8_t> rgba(expected, 0);
  int imageCount = 0;
#if MEDIA3_LIBASS_HAS_NATIVE
  for (const auto& track : state->tracks) {
    if (track.enabled && track.ass) {
      int changed = 0;
      ASS_Image* image = ass_render_frame(state->renderer, track.ass, static_cast<long long>(positionUs / 1000), &changed);
      for (ASS_Image* current = image; current != nullptr; current = current->next) ++imageCount;
      blendImage(image, state->width, state->height, rgba.data());
    }
  }
#else
  (void)positionUs;
#endif
  env->SetByteArrayRegion(output, 0, expected, reinterpret_cast<const jbyte*>(rgba.data()));
#if MEDIA3_LIBASS_HAS_NATIVE
  __android_log_print(ANDROID_LOG_DEBUG, kTag, "render positionUs=%lld tracks=%zu images=%d",
      static_cast<long long>(positionUs), state->tracks.size(), imageCount);
  return imageCount > 0 ? JNI_TRUE : JNI_FALSE;
#else
  return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeRelease(JNIEnv*, jclass, jlong handle) {
  auto* state = fromHandle(handle);
  if (!state) return;
  std::lock_guard lock(state->mutex);
#if MEDIA3_LIBASS_HAS_NATIVE
  for (auto& track : state->tracks) if (track.ass) ass_free_track(track.ass);
  if (state->renderer) ass_renderer_done(state->renderer);
  if (state->library) ass_library_done(state->library);
#endif
  if (state->window) ANativeWindow_release(state->window);
  delete state;
}
