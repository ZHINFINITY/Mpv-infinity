#!/usr/bin/env python3
"""Regression checks for the AudioTrack preferred-device routing patch."""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import patch_audiotrack_device as PATCHER  # noqa: E402

MINIMAL_SOURCE = """#include "misc/jni.h"

static mp_static_mutex jni_static_lock = MP_STATIC_MUTEX_INITIALIZER;
static int jni_static_use_count = 0;

static struct JNIAudioTrack {
    jmethodID getLatency;
    jmethodID getMinBufferSize;
} AudioTrack;

static const struct MPJniField AudioTrack_mapping[] = {
    {"getLatency", "()I", MP_JNI_METHOD, OFFSET(getLatency), 1},
    {"getMinBufferSize", "(III)I", MP_JNI_STATIC_METHOD, OFFSET(getMinBufferSize), 1},
};

static int AudioTrack_New(struct ao *ao)
{
    jobject audiotrack = AudioTrack_New_impl(ao);
    if (AudioTrack.getBufferSizeInFramesV23) {
        return 0;
    }
    return audiotrack != NULL ? 0 : -1;
}
"""


def check(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def apply_to(source: str) -> tuple[str, bool]:
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "ao_audiotrack.c"
        path.write_text(source, encoding="utf-8")
        changed = PATCHER.apply_patch(path)
        return path.read_text(encoding="utf-8"), changed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", type=Path,
                       help="optional pinned MPV source to patch a copy of")
    args = parser.parse_args()

    failures: list[str] = []

    patched, changed = apply_to(MINIMAL_SOURCE)
    check(changed, "first apply must report a change", failures)
    check(PATCHER.MARKER in patched, "marker missing after apply", failures)
    check(
        '__attribute__((visibility("default")))\nvoid mpv_audiotrack_set_preferred_device('
        in patched,
        "exported device entry point missing",
        failures,
    )
    check(
        "jmethodID setPreferredDevice;" in patched,
        "AudioTrack struct gained no setPreferredDevice id",
        failures,
    )
    check(
        '{"setPreferredDevice", "(Landroid/media/AudioDeviceInfo;)Z", MP_JNI_METHOD,'
        in patched,
        "setPreferredDevice mapping entry missing",
        failures,
    )
    check(
        "audio_track_apply_preferred_device(ao, audiotrack)" in patched,
        "AudioTrack_New never applies the chosen device",
        failures,
    )
    check(
        patched.index("audio_track_apply_preferred_device(struct ao *ao, jobject audiotrack)")
        < patched.index("static int AudioTrack_New(struct ao *ao)"),
        "helper must be defined before AudioTrack_New",
        failures,
    )
    check(
        patched.index("static struct JNIAudioTrack")
        < patched.index("audio_track_apply_preferred_device(struct ao *ao, jobject audiotrack)"),
        "helper must be defined after the AudioTrack JNI table",
        failures,
    )

    reapplied, changed_again = apply_to(patched)
    check(not changed_again, "second apply must be a no-op", failures)
    check(reapplied == patched, "second apply must not rewrite the file", failures)

    try:
        apply_to(MINIMAL_SOURCE.replace("static int AudioTrack_New(struct ao *ao)", "static int Other(void)"))
    except ValueError:
        pass
    else:
        failures.append("missing anchor must raise ValueError")

    prepare = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
    check(
        "from patch_audiotrack_device import apply_patch as apply_audiotrack_device_patch"
        in prepare,
        "prepare-mpvflow.py does not import the device routing patch",
        failures,
    )
    check(
        "apply_audiotrack_device_patch(" in prepare and '"audio/out/ao_audiotrack.c"' in prepare,
        "prepare-mpvflow.py never applies the device routing patch",
        failures,
    )

    if args.mpv_dir:
        source_path = args.mpv_dir.resolve() / "audio/out/ao_audiotrack.c"
        original = source_path.read_text(encoding="utf-8")
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp) / "ao_audiotrack.c"
            copy.write_text(original, encoding="utf-8")
            check(PATCHER.apply_patch(copy), "pinned source was already patched", failures)
            check(PATCHER.MARKER in copy.read_text(encoding="utf-8"),
                  "marker missing from the pinned source copy", failures)

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)
    print("AudioTrack preferred-device routing patch checks passed")


if __name__ == "__main__":
    main()
