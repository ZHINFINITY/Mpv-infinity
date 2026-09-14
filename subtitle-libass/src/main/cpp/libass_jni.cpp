#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
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
#if MEDIA3_LIBASS_HAS_NATIVE
  ASS_Library* library = nullptr;
  ASS_Renderer* renderer = nullptr;
#endif
};
Renderer* fromHandle(jlong handle) { return reinterpret_cast<Renderer*>(handle); }

#if MEDIA3_LIBASS_HAS_NATIVE
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
Java_androidx_media3_subtitle_libass_LibassNative_nativeAppendEvent(JNIEnv* env, jclass, jlong handle, jint id, jbyteArray data) {
  auto* state = fromHandle(handle);
  if (!state || !data) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
  auto it = std::find_if(state->tracks.begin(), state->tracks.end(), [id](const Track& t) { return t.id == id; });
  if (it == state->tracks.end()) return JNI_FALSE;
#if MEDIA3_LIBASS_HAS_NATIVE
  jsize size = env->GetArrayLength(data);
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (!bytes) return JNI_FALSE;
  ass_process_chunk(it->ass, reinterpret_cast<char*>(bytes), size, 0, 0);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
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

extern "C" JNIEXPORT jboolean JNICALL
Java_androidx_media3_subtitle_libass_LibassNative_nativeRenderRgba(JNIEnv* env, jclass, jlong handle, jlong positionUs, jbyteArray output) {
  auto* state = fromHandle(handle);
  if (!state || !output) return JNI_FALSE;
  const jsize expected = state->width * state->height * 4;
  if (env->GetArrayLength(output) < expected) return JNI_FALSE;
  std::lock_guard lock(state->mutex);
  std::vector<uint8_t> rgba(expected, 0);
#if MEDIA3_LIBASS_HAS_NATIVE
  for (const auto& track : state->tracks) {
    if (track.enabled && track.ass) {
      int changed = 0;
      ASS_Image* image = ass_render_frame(state->renderer, track.ass, static_cast<long long>(positionUs / 1000), &changed);
      blendImage(image, state->width, state->height, rgba.data());
    }
  }
#else
  (void)positionUs;
#endif
  env->SetByteArrayRegion(output, 0, expected, reinterpret_cast<const jbyte*>(rgba.data()));
#if MEDIA3_LIBASS_HAS_NATIVE
  return state->tracks.empty() ? JNI_FALSE : JNI_TRUE;
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
  delete state;
}
