#!/usr/bin/env python3
"""Fixture tests for per-ABI selection and GPL patches in the AAR builder."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BUILDER = ROOT / "scripts/build-mpv-lgpl-aar.sh"
ABI_MAP = {
    "arm64-v8a": "arm64",
    "armeabi-v7a": "armv7l",
    "x86": "x86",
    "x86_64": "x86_64",
}
ABIS = tuple(ABI_MAP)

OLD_CI_ARCHES = (
    "ci_build_arches=(\n"
    "\tarmv7l\n"
    "\tarm64\n"
    ")\n"
    '[ "$ENABLE_X86_ARCH" = "true" ] && ci_build_arches+=(\n'
    "\tx86\n"
    "\tx86_64\n"
    ")\n"
)
OLD_MPV_FETCH = (
    '\tmsg "Fetching mpv"\n'
    "\tif [ ! -d deps/mpv ]; then\n"
    "\t\tgit clone --depth 1 https://github.com/mpv-player/mpv deps/mpv\n"
    "\telse\n"
    "\t\tgit -C deps/mpv fetch --depth 1 origin master\n"
    "\t\tgit -C deps/mpv reset --hard origin/master\n"
    "\tfi\n"
    "\tgit -C deps/mpv apply ../../patches/mpv_video_shaders.patch\n"
)
OLD_RUBBERBAND_DOWNLOAD = (
    "# rubberband\n"
    "if [ ! -d rubberband ]; then\n"
    "\tmkdir rubberband\n"
    "\t$WGET https://github.com/breakfastquay/rubberband/archive/refs/tags/v${v_rubberband}.tar.gz -O - | \\\n"
    "\t\ttar -xz -C rubberband --strip-components=1\n"
    "fi\n\n"
)


def run_selector(value: str | None) -> subprocess.CompletedProcess[str]:
    source = BUILDER.read_text(encoding="utf-8")
    start = source.index("DEFAULT_ABIS=")
    end = source.index("VARIANTS=(", start)
    selection_block = source[start:end]
    env = os.environ.copy()
    env.pop("MPV_LGPL_ABIS", None)
    if value is not None:
        env["MPV_LGPL_ABIS"] = value
    command = (
        "set -euo pipefail\n"
        + selection_block
        + "printf '%s|%s\\n' \"$ABIS\" \"${ARCHES[*]}\"\n"
    )
    return subprocess.run(
        ["bash", "-c", command], capture_output=True, text=True, env=env, check=False
    )


def test_abi_selection() -> None:
    for abi, arch in ABI_MAP.items():
        result = run_selector(abi)
        assert result.returncode == 0, result.stderr
        assert result.stdout.strip() == f"{abi}|{arch}", result.stdout

    all_result = run_selector(None)
    expected_abis = ",".join(ABIS)
    expected_arches = " ".join(ABI_MAP[abi] for abi in ABIS)
    assert all_result.returncode == 0, all_result.stderr
    assert all_result.stdout.strip() == f"{expected_abis}|{expected_arches}"

    for invalid in ("arm64-v8a,arm64-v8a", "mips", "arm64-v8a,,x86"):
        result = run_selector(invalid)
        assert result.returncode != 0, f"invalid ABI list unexpectedly passed: {invalid}"


def run_source_patcher(fixture: Path) -> tuple[str, str, str, str]:
    ci = fixture / "ci.sh"
    depinfo = fixture / "depinfo.sh"
    download = fixture / "download-deps.sh"
    config = fixture / "build_config.sh"
    ffmpeg = fixture / "ffmpeg.sh"
    mpv = fixture / "mpv.sh"

    ci.write_text(OLD_CI_ARCHES + OLD_MPV_FETCH, encoding="utf-8")
    depinfo.write_text(
        "v_rubberband=4.0.0\n"
        "dep_rubberband=()\n"
        "v_ci_ffmpeg=n9.0\n"
        "dep_mpv=( ffmpeg rubberband zlib )\n"
        "v_unused=-rubberband-${v_rubberband}\n",
        encoding="utf-8",
    )
    download.write_text(
        "[ ! -d dav1d ] && git clone https://github.com/videolan/dav1d\n"
        "[ ! -d libass ] && git clone https://github.com/libass/libass\n"
        "[ ! -d libplacebo ] && git clone --recursive https://github.com/haasn/libplacebo\n"
        + OLD_RUBBERBAND_DOWNLOAD,
        encoding="utf-8",
    )
    config.write_text("ENABLE_X86_ARCH=true\n", encoding="utf-8")
    ffmpeg.write_text(
        "--disable-static --enable-shared --enable-{gpl,version3}\n",
        encoding="utf-8",
    )
    mpv.write_text(
        "-D{lua,libcurl,rubberband}=enabled\n"
        "-D{libarchive,dvdnav}=enabled\n"
        "-Dlibmpv=true -Dcplayer=false \\\n"
        "-Dvulkan=enabled\n",
        encoding="utf-8",
    )

    source = BUILDER.read_text(encoding="utf-8")
    marker = "<<'PY'\n"
    body_start = source.index(marker) + len(marker)
    body_end = source.index("\nPY\n", body_start)
    patcher = source[body_start:body_end]
    result = subprocess.run(
        [
            sys.executable,
            "-",
            str(ci),
            str(depinfo),
            str(download),
            str(config),
            str(ffmpeg),
            str(mpv),
            "mpv-commit",
            "libplacebo-commit",
            "dav1d-commit",
            "libass-commit",
        ],
        input=patcher,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr or result.stdout
    return ci.read_text(), depinfo.read_text(), ffmpeg.read_text(), mpv.read_text()


def test_source_patcher() -> None:
    with tempfile.TemporaryDirectory(prefix="mpv-lgpl-patcher-test-") as temporary:
        ci, depinfo, ffmpeg, mpv = run_source_patcher(Path(temporary))
        selector = (
            'read -r -a ci_build_arches <<< '
            '"${MPV_LGPL_ARCHES:-armv7l arm64 x86 x86_64}"'
        )
        assert selector in ci, "pinned CI installer must honor matrix-selected architectures"
        assert "ENABLE_X86_ARCH" not in ci, "upstream all-ABI loop must be replaced"
        assert "Fetching pinned MPV source" in ci
        assert "--disable-gpl --disable-nonfree --enable-version3" in ffmpeg
        assert "--enable-{gpl,version3}" not in ffmpeg
        assert "-Dgpl=false" in mpv
        assert "-Dvulkan=enabled" in mpv
        assert "rubberband" not in depinfo.lower()
        assert "rubberband" not in mpv.lower()
        assert "-D{libarchive,dvdnav}=enabled" not in mpv
        assert "-Ddvdnav=disabled" in mpv

        selected = subprocess.run(
            ["bash", "-c", selector + "\nprintf '%s\\n' \"${ci_build_arches[*]}\""],
            capture_output=True,
            text=True,
            env={**os.environ, "MPV_LGPL_ARCHES": "arm64"},
            check=False,
        )
        assert selected.returncode == 0, selected.stderr
        assert selected.stdout.strip() == "arm64", selected.stdout


def main() -> None:
    test_abi_selection()
    test_source_patcher()
    print("MPV LGPL builder matrix and GPL-patch tests passed.")


if __name__ == "__main__":
    main()
