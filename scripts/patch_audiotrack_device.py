#!/usr/bin/env python3
"""Route mpv's AudioTrack audio output to an app-chosen device.

The pinned mpv runtime's `ao_audiotrack.c` builds its own `android.media.AudioTrack` and never calls
`AudioTrack.setPreferredDevice`, so a picked output (Bluetooth -> speaker, USB, HDMI,
line out) cannot reach the mpv music session: the platform keeps using its default
route. ExoPlayer routes itself by calling that method on its own track; this patch
gives the mpv driver the same ability.

Only a `Context` can enumerate `AudioDeviceInfo`s, so the Java side owns the device
object and hands it over through one exported JNI entry point,
`mpv_audiotrack_set_preferred_device`, which stores a global reference. Every
`AudioTrack` the driver builds afterwards is bound to it; setting null restores the
platform default. Kotlin re-runs the audio output with `ao-reload` so a live track is
rebuilt against the new device.

With no device set the driver behaves exactly as before, so video playback and frame
interpolation are untouched.
"""
from __future__ import annotations

import sys
from pathlib import Path

MARKER = "MPV_INFINITY_AUDIOTRACK_ROUTE_V1"

GLOBAL_ANCHOR = "static mp_static_mutex jni_static_lock = MP_STATIC_MUTEX_INITIALIZER;\n"

GLOBAL_PATCH = """/* {marker}: lets the app pin the music session to a chosen output device. */
static mp_static_mutex route_device_lock = MP_STATIC_MUTEX_INITIALIZER;
static jobject route_device_ref;

__attribute__((visibility("default")))
void mpv_audiotrack_set_preferred_device(JNIEnv *env, jobject device)
{{
    if (!env)
        return;
    jobject new_ref = device ? (*env)->NewGlobalRef(env, device) : NULL;
    if (device && !new_ref)
        return;
    mp_mutex_lock(&route_device_lock);
    jobject old_ref = route_device_ref;
    route_device_ref = new_ref;
    mp_mutex_unlock(&route_device_lock);
    if (old_ref)
        (*env)->DeleteGlobalRef(env, old_ref);
}}

""".format(marker=MARKER)

STRUCT_ANCHOR = "    jmethodID getLatency;\n    jmethodID getMinBufferSize;\n"

STRUCT_PATCH = """    jmethodID getLatency;
    jmethodID setPreferredDevice;
    jmethodID getMinBufferSize;
"""

MAPPING_ANCHOR = (
    '    {"getMinBufferSize", "(III)I", MP_JNI_STATIC_METHOD, OFFSET(getMinBufferSize), 1},\n'
)

MAPPING_PATCH = (
    '    {"setPreferredDevice", "(Landroid/media/AudioDeviceInfo;)Z", MP_JNI_METHOD,\n'
    "     OFFSET(setPreferredDevice), 0},\n" + MAPPING_ANCHOR
)

APPLY_ANCHOR = "static int AudioTrack_New(struct ao *ao)\n"

APPLY_PATCH = """static int audio_track_apply_preferred_device(struct ao *ao, jobject audiotrack)
{
    JNIEnv *env = MP_JNI_GET_ENV(ao);
    if (!env || !AudioTrack.setPreferredDevice)
        return 0;
    mp_mutex_lock(&route_device_lock);
    jobject device = route_device_ref ? (*env)->NewLocalRef(env, route_device_ref) : NULL;
    mp_mutex_unlock(&route_device_lock);
    if (!device)
        return 0;
    jboolean bound = (*env)->CallBooleanMethod(env, audiotrack,
                                               AudioTrack.setPreferredDevice, device);
    (*env)->DeleteLocalRef(env, device);
    if (MP_JNI_EXCEPTION_LOG(ao) < 0) {
        MP_WARN(ao, "AudioTrack.setPreferredDevice failed\\n");
        return -1;
    }
    MP_VERBOSE(ao, "AudioTrack bound to the preferred output device\\n");
    return bound ? 0 : -1;
}

"""

STATE_ANCHOR = "    if (AudioTrack.getBufferSizeInFramesV23) {\n"

STATE_PATCH = """    if (audio_track_apply_preferred_device(ao, audiotrack) < 0)
        MP_WARN(ao, "Continuing on the platform default output\\n");

""" + STATE_ANCHOR


def _replace_once(source: str, anchor: str, patch: str, label: str) -> str:
    count = source.count(anchor)
    if count != 1:
        raise ValueError(f"expected exactly one {label} anchor, found {count}")
    return source.replace(anchor, patch, 1)


def apply_patch(ao_path: Path) -> bool:
    """Patch `ao_audiotrack.c` in place; returns True when this call changed it."""
    source = ao_path.read_text(encoding="utf-8")
    if MARKER in source:
        return False
    patched = _replace_once(source, GLOBAL_ANCHOR, GLOBAL_PATCH + GLOBAL_ANCHOR, "global")
    patched = _replace_once(patched, STRUCT_ANCHOR, STRUCT_PATCH, "AudioTrack struct")
    patched = _replace_once(patched, MAPPING_ANCHOR, MAPPING_PATCH, "AudioTrack mapping")
    patched = _replace_once(patched, APPLY_ANCHOR, APPLY_PATCH + APPLY_ANCHOR, "AudioTrack_New")
    patched = _replace_once(patched, STATE_ANCHOR, STATE_PATCH, "AudioTrack state check")
    ao_path.write_text(patched, encoding="utf-8")
    return True


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch_audiotrack_device.py <mpv-dir>", file=sys.stderr)
        return 2
    ao_path = Path(sys.argv[1]) / "audio/out/ao_audiotrack.c"
    if not ao_path.is_file():
        print(f"missing {ao_path}", file=sys.stderr)
        return 1
    changed = apply_patch(ao_path)
    print(
        "Applied AudioTrack preferred-device routing"
        if changed
        else "AudioTrack preferred-device routing already present"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
