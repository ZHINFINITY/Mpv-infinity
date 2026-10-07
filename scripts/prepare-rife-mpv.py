#!/usr/bin/env python3
"""Register CPU RIFE and apply the experimental resident Vulkan/NCNN VO patch."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "app/src/main/cpp/rife"
RESIDENT_VO_PATCH = ROOT / "scripts/patches/rife-resident-vo.patch"
PINNED_MPV_REVISION = "c1529642089bfebfc928a1c1664638a7a5d219ba"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def base_sources_close(meson: str) -> int:
    start = meson.index("sources = files(")
    depth = 0
    quote = None
    escaped = False
    comment = False
    for pos in range(start, len(meson)):
        char = meson[pos]
        if comment:
            if char == "\n":
                comment = False
            continue
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char == "#":
            comment = True
        elif char in "'\"":
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return pos
    raise SystemExit("Could not find end of MPV base source list")


def apply_resident_vo_patch(mpv_dir: Path) -> None:
    if not RESIDENT_VO_PATCH.is_file():
        raise SystemExit(f"RIFE resident VO patch is missing: {RESIDENT_VO_PATCH}")
    source_dir = Path(os.environ.get("MPV_SOURCE_DIR", mpv_dir)).resolve()
    try:
        revision = subprocess.check_output(
            ["git", "-C", str(source_dir), "rev-parse", "HEAD"], text=True
        ).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Unable to identify pinned MPV source revision: {error}")
    if revision != PINNED_MPV_REVISION:
        raise SystemExit(
            f"RIFE resident VO patch expects MPV {PINNED_MPV_REVISION}, found {revision}"
        )

    vo_path = mpv_dir / "video/out/vo_gpu_next.c"
    if "rife_cadence_grid_index" in vo_path.read_text():
        return
    subprocess.run(
        ["git", "-C", str(mpv_dir), "apply", "--check", str(RESIDENT_VO_PATCH)],
        check=True,
    )
    subprocess.run(
        ["git", "-C", str(mpv_dir), "apply", str(RESIDENT_VO_PATCH)],
        check=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    parser.add_argument("--prefix-dir", required=True, type=Path)
    args = parser.parse_args()
    mpv_dir = args.mpv_dir.resolve()
    prefix_dir = args.prefix_dir.resolve()
    runtime_library = prefix_dir / "lib/librife_vfi.so"
    for path in (mpv_dir / "meson.build", mpv_dir / "filters/user_filters.h", mpv_dir / "filters/user_filters.c"):
        if not path.is_file():
            raise SystemExit(f"Pinned MPV source is missing {path}")
    if not runtime_library.is_file():
        raise SystemExit(f"RIFE shared runtime is missing: {runtime_library}")
    for name in ("vf_rife.c", "rife_vfi.h", "rife_cadence.h"):
        if not (VENDOR / name).is_file():
            raise SystemExit(f"RIFE MPV source is missing: {VENDOR / name}")

    video_filter_dir = mpv_dir / "video/filter"
    shutil.copy2(VENDOR / "vf_rife.c", video_filter_dir / "vf_rife.c")
    shutil.copy2(VENDOR / "rife_vfi.h", video_filter_dir / "rife_vfi.h")
    shutil.copy2(VENDOR / "rife_cadence.h", video_filter_dir / "rife_cadence.h")
    apply_resident_vo_patch(mpv_dir)

    meson_path = mpv_dir / "meson.build"
    meson = meson_path.read_text()
    if "# RIFE_ANDROID_FILTER" not in meson:
        close = base_sources_close(meson)
        block = (
            "\n# RIFE is an opt-in Android filter linked to the pinned ncnn/Vulkan runtime.\n"
            "if host_machine.system() == 'android'\n"
            "    add_project_arguments('-DMPV_HAS_RIFE', language: 'c')\n"
            "    sources += files('video/filter/vf_rife.c')\n"
            f"    rife_vfi_dep = declare_dependency(link_args: ['-L{prefix_dir / 'lib'}', '-lrife_vfi'])\n"
            "    dependencies += [rife_vfi_dep]\n"
            "endif\n"
            "# RIFE_ANDROID_FILTER\n"
        )
        meson = meson[:close + 1] + block + meson[close + 1:]
        meson_path.write_text(meson)

    user_h_path = mpv_dir / "filters/user_filters.h"
    user_h = user_h_path.read_text()
    if "vf_rife" not in user_h:
        user_h = replace_once(
            user_h,
            "extern const struct mp_user_filter_entry vf_sub;",
            "extern const struct mp_user_filter_entry vf_sub;\n"
            "#ifdef __ANDROID__\nextern const struct mp_user_filter_entry vf_rife;\n#endif",
            "MPV user-filter declarations",
        )
        user_h_path.write_text(user_h)

    user_c_path = mpv_dir / "filters/user_filters.c"
    user_c = user_c_path.read_text()
    if "&vf_rife" not in user_c:
        user_c = replace_once(
            user_c,
            "    &vf_sub,",
            "    &vf_sub,\n#ifdef __ANDROID__\n    &vf_rife,\n#endif",
            "MPV user-filter registry",
        )
        user_c_path.write_text(user_c)

    builder_dir = prefix_dir.parents[2]
    android_mk_path = builder_dir / "app/src/main/jni/Android.mk"
    if not android_mk_path.is_file():
        raise SystemExit(f"Android MPV builder packaging file is missing: {android_mk_path}")
    android_mk = android_mk_path.read_text()
    module_marker = "# RIFE_VFI_PREBUILT\n"
    if module_marker not in android_mk:
        anchor = "include $(CLEAR_VARS)\n\nLOCAL_MODULE    := libplayer"
        module = (
            "include $(CLEAR_VARS)\n"
            "LOCAL_MODULE := rife_vfi\n"
            "LOCAL_SRC_FILES := $(PREFIX)/lib/librife_vfi.so\n"
            "include $(PREBUILT_SHARED_LIBRARY)\n"
            "# RIFE_VFI_PREBUILT\n\n"
            "include $(CLEAR_VARS)\n\nLOCAL_MODULE    := libplayer"
        )
        android_mk = replace_once(android_mk, anchor, module, "libplayer NDK module")
    if "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv rife_vfi" not in android_mk:
        android_mk = replace_once(
            android_mk,
            "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv",
            "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv rife_vfi",
            "player shared-library list",
        )
    android_mk_path.write_text(android_mk)
    print(f"Registered vf_rife and packaged librife_vfi.so in {mpv_dir}")


if __name__ == "__main__":
    main()
