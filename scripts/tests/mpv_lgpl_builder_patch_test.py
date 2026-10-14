#!/usr/bin/env python3
"""Fixture tests for per-ABI selection and GPL patches in the AAR builder."""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from unittest.mock import patch

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
VERSION_GENERATOR_FIXTURE = (
    "# Meson writes the version used by the compiled library to this generated header.\n"
    "old per-ABI version logic\n"
    "# Installed pkg-config files survive CI prefix cache restores and describe the\n"
    "old dependency version logic\n"
    "# Get the build date from mpv's compiled object file.\n"
    "old ELF date parser\n"
    "versions_file=app/src/main/java/is/xyz/mpv/Utils.kt\n"
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


def run_source_patcher(fixture: Path) -> tuple[str, str, str, str, str]:
    ci = fixture / "ci.sh"
    depinfo = fixture / "depinfo.sh"
    download = fixture / "download-deps.sh"
    config = fixture / "build_config.sh"
    ffmpeg = fixture / "ffmpeg.sh"
    mpv = fixture / "mpv.sh"
    versions = fixture / "write_versions.sh"

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
    versions.write_text(VERSION_GENERATOR_FIXTURE, encoding="utf-8")

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
            str(versions),
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
    return ci.read_text(), depinfo.read_text(), ffmpeg.read_text(), mpv.read_text(), versions.read_text()


def test_source_patcher() -> None:
    with tempfile.TemporaryDirectory(prefix="mpv-lgpl-patcher-test-") as temporary:
        ci, depinfo, ffmpeg, mpv, versions = run_source_patcher(Path(temporary))
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
        assert "find buildscripts/deps/mpv" in versions
        assert "MPV version differs between generated ABI headers" in versions
        assert "DATE=unknown" in versions
        assert "old ELF date parser" not in versions
        assert "OBJ_FILE=" not in versions

        selected = subprocess.run(
            ["bash", "-c", selector + "\nprintf '%s\\n' \"${ci_build_arches[*]}\""],
            capture_output=True,
            text=True,
            env={**os.environ, "MPV_LGPL_ARCHES": "arm64"},
            check=False,
        )
        assert selected.returncode == 0, selected.stderr
        assert selected.stdout.strip() == "arm64", selected.stdout

        header_value = 'v0.41.0-dev-gf5bcfb195-dirty'
        mpv_root = Path(temporary) / "buildscripts/deps/mpv"
        headers = [
            mpv_root / "_build-arm64/common/version.h",
            mpv_root / "_build/common/version.h",
        ]
        for header in headers:
            header.parent.mkdir(parents=True, exist_ok=True)
            header.write_text(f'#define VERSION "{header_value}"\n', encoding="utf-8")

        version_start = versions.index("# Read all generated MPV version headers")
        version_end = versions.index(
            "# Installed pkg-config files survive CI prefix cache restores and describe the",
            version_start,
        )
        version_logic = versions[version_start:version_end]
        selected_version = subprocess.run(
            ["bash", "-c", version_logic + '\nprintf \'%s\\n\' "$MPV_VERSION"'],
            cwd=temporary,
            capture_output=True,
            text=True,
            check=False,
        )
        assert selected_version.returncode == 0, selected_version.stderr
        assert selected_version.stdout.strip() == header_value.removeprefix("v")

        headers[1].write_text('#define VERSION "v0.42.0-dev-mismatch"\n', encoding="utf-8")
        rejected_version = subprocess.run(
            ["bash", "-c", version_logic],
            cwd=temporary,
            capture_output=True,
            text=True,
            check=False,
        )
        assert rejected_version.returncode != 0
        assert "MPV version differs between generated ABI headers" in rejected_version.stderr


def test_libplacebo_api_alignment() -> None:
    builder = BUILDER.read_text(encoding="utf-8")
    flow_builder = (ROOT / "scripts/build-mpvflow-mpv-runtime.sh").read_text(encoding="utf-8")
    header = (ROOT / "app/src/main/cpp/third_party/libplacebo/include/libplacebo/config.h").read_text(
        encoding="utf-8"
    )
    cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
    verifier_source = (ROOT / "scripts/verify-mpv-aar-lgpl.py").read_text(encoding="utf-8")

    aar_revision = re.search(r'^LIBPLACEBO_COMMIT="([0-9a-f]{40})"$', builder, re.MULTILINE)
    flow_revision = re.search(r'libplacebo_revision = "([0-9a-f]{40})"', flow_builder)
    api_match = re.search(r"^#define PL_API_VER\s+(\d+)\s*$", header, re.MULTILINE)
    assert aar_revision and flow_revision and api_match
    assert aar_revision.group(1) == flow_revision.group(1), (
        "LGPL AAR and MPVFlow native builders must use the same libplacebo source revision"
    )
    api_version = api_match.group(1)
    expected_symbol = f"pl_log_create_{api_version}"
    assert '"pl_log_create_${MPVFLOW_EXPECTED_PLACEBO_API_VER}"' in cmake
    assert "verify_placebo_logger_export(abi, path)" in verifier_source

    spec = importlib.util.spec_from_file_location(
        "verify_mpv_aar_lgpl_test", ROOT / "scripts/verify-mpv-aar-lgpl.py"
    )
    assert spec is not None and spec.loader is not None
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)

    with patch.object(verifier, "run_tool", return_value=f"00000000 T {expected_symbol}"):
        verifier.verify_placebo_logger_export("arm64-v8a", Path("libmpv.so"))

    try:
        with patch.object(verifier, "run_tool", return_value="00000000 T pl_log_create_371"):
            verifier.verify_placebo_logger_export("arm64-v8a", Path("libmpv.so"))
    except SystemExit as error:
        assert expected_symbol in str(error)
        assert "pl_log_create_371" in str(error)
    else:
        raise AssertionError("the AAR verifier accepted a mismatched libplacebo logger API")


def main() -> None:
    test_abi_selection()
    test_source_patcher()
    test_libplacebo_api_alignment()
    print("MPV LGPL builder matrix and GPL-patch tests passed.")


if __name__ == "__main__":
    main()
