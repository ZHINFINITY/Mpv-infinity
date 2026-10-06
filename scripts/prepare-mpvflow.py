#!/usr/bin/env python3
"""Register the independent MPVFlow MEMC filter in the pinned Android mpv checkout."""
from __future__ import annotations

import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "app/src/main/cpp/mpvflow"


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
    raise SystemExit("Could not find end of mpv base source list")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    args = parser.parse_args()
    mpv_dir = args.mpv_dir.resolve()
    for path in (mpv_dir / "meson.build", mpv_dir / "filters/user_filters.h", mpv_dir / "filters/user_filters.c"):
        if not path.is_file():
            raise SystemExit(f"Pinned mpv source is missing {path}")
    for name in ("vf_mpvflow.c", "mpvflow_core.c", "mpvflow_core.h"):
        if not (VENDOR / name).is_file():
            raise SystemExit(f"MPVFlow source is missing: {VENDOR / name}")

    video_filter_dir = mpv_dir / "video/filter"
    video_filter_dir.mkdir(parents=True, exist_ok=True)
    for name in ("vf_mpvflow.c", "mpvflow_core.c", "mpvflow_core.h"):
        shutil.copy2(VENDOR / name, video_filter_dir / name)

    meson_path = mpv_dir / "meson.build"
    meson = meson_path.read_text()
    if "# MPVFLOW_ANDROID_FILTER" not in meson:
        close = base_sources_close(meson)
        block = (
            "\n# MPVFlow is a standalone on-device MEMC experiment, separate from RIFE.\n"
            "if host_machine.system() == 'android'\n"
            "    sources += files('video/filter/vf_mpvflow.c', 'video/filter/mpvflow_core.c')\n"
            "endif\n"
            "# MPVFLOW_ANDROID_FILTER\n"
        )
        meson = meson[:close + 1] + block + meson[close + 1:]
        meson_path.write_text(meson)

    user_h_path = mpv_dir / "filters/user_filters.h"
    user_h = user_h_path.read_text()
    if "vf_mpvflow" not in user_h:
        rife_block = (
            "#ifdef __ANDROID__\n"
            "extern const struct mp_user_filter_entry vf_rife;\n"
            "#endif"
        )
        if rife_block in user_h:
            user_h = replace_once(
                user_h,
                rife_block,
                rife_block.replace("#endif", "extern const struct mp_user_filter_entry vf_mpvflow;\n#endif"),
                "Android RIFE declarations",
            )
        else:
            user_h = replace_once(
                user_h,
                "extern const struct mp_user_filter_entry vf_sub;",
                "extern const struct mp_user_filter_entry vf_sub;\n"
                "#ifdef __ANDROID__\nextern const struct mp_user_filter_entry vf_mpvflow;\n#endif",
                "mpv user-filter declarations",
            )
        user_h_path.write_text(user_h)

    user_c_path = mpv_dir / "filters/user_filters.c"
    user_c = user_c_path.read_text()
    if "&vf_mpvflow" not in user_c:
        if "    &vf_rife," in user_c:
            user_c = replace_once(
                user_c,
                "    &vf_rife,",
                "    &vf_rife,\n    &vf_mpvflow,",
                "Android user-filter registry",
            )
        else:
            user_c = replace_once(
                user_c,
                "    &vf_sub,",
                "    &vf_sub,\n#ifdef __ANDROID__\n    &vf_mpvflow,\n#endif",
                "mpv user-filter registry",
            )
        user_c_path.write_text(user_c)

    print(f"Registered the independent vf_mpvflow filter in {mpv_dir}")


if __name__ == "__main__":
    main()
