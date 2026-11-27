/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 *
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */

/*
 * Bridges a user-chosen audio output to mpv's AudioTrack driver.
 *
 * mpv's Android audio output builds its own `android.media.AudioTrack` inside
 * libmpv, so only libmpv can call `AudioTrack.setPreferredDevice` on it. The device
 * object itself can only be obtained through the app's Context, so the Kotlin side
 * resolves the `AudioDeviceInfo` and hands the reference here; this bridge forwards it
 * to the `mpv_audiotrack_set_preferred_device` entry point that the patched
 * `ao_audiotrack.c` exports.
 *
 * Returns true when libmpv exposes that entry point, so the caller knows whether the
 * pick reached the engine instead of silently believing it did.
 */

#include <dlfcn.h>
#include <jni.h>

namespace {

using SetDeviceFn = void (*)(JNIEnv *, jobject);

SetDeviceFn resolve_set_device() {
  static SetDeviceFn fn = []() -> SetDeviceFn {
    if (void *handle = dlopen("libmpv.so", RTLD_NOW | RTLD_NOLOAD)) {
      if (auto *symbol = dlsym(handle, "mpv_audiotrack_set_preferred_device"))
        return reinterpret_cast<SetDeviceFn>(symbol);
    }
    if (auto *symbol = dlsym(RTLD_DEFAULT, "mpv_audiotrack_set_preferred_device"))
      return reinterpret_cast<SetDeviceFn>(symbol);
    return nullptr;
  }();
  return fn;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_infinity_mpvz_ui_browser_music_MpvAudioRoute_nativeSetPreferredDevice(
    JNIEnv *env, jobject /*thiz*/, jobject device) {
  SetDeviceFn set_device = resolve_set_device();
  if (!set_device)
    return JNI_FALSE;
  set_device(env, device);
  return JNI_TRUE;
}
