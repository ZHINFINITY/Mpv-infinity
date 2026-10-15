#!/usr/bin/env python3
"""Fixture tests for packaging the LGPL libudfread runtime in MPV AARs."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGER = ROOT / "scripts/package-mpv-aar-udfread.py"
ABI_ARCH = {
    "arm64-v8a": "arm64",
    "armeabi-v7a": "armv7l",
    "x86": "x86",
    "x86_64": "x86_64",
}


def create_aar(path: Path, abis: tuple[str, ...]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr("classes.jar", b"classes")
        for abi in abis:
            archive.writestr(f"jni/{abi}/libmpv.so", f"mpv-{abi}".encode())
            archive.writestr(f"jni/{abi}/libavcodec.so", f"codec-{abi}".encode())


def run_packager(aar: Path, prefix: Path, abis: tuple[str, ...]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(PACKAGER),
            str(aar),
            str(prefix),
            "--abis",
            ",".join(abis),
        ],
        capture_output=True,
        text=True,
        check=False,
    )


def main() -> None:
    selected_abis = ("arm64-v8a", "x86_64")
    with tempfile.TemporaryDirectory(prefix="mpv-lgpl-udfread-test-") as temporary:
        root = Path(temporary)
        prefix = root / "prefix"
        aar = root / "mpvlib.aar"
        create_aar(aar, selected_abis)

        expected: dict[str, bytes] = {}
        for abi in selected_abis:
            payload = f"udfread-{abi}".encode()
            expected[abi] = payload
            library = prefix / ABI_ARCH[abi] / "lib/libudfread.so"
            library.parent.mkdir(parents=True, exist_ok=True)
            library.write_bytes(payload)

        first = run_packager(aar, prefix, selected_abis)
        assert first.returncode == 0, first.stderr or first.stdout
        with zipfile.ZipFile(aar) as archive:
            assert archive.testzip() is None
            for abi, payload in expected.items():
                assert archive.read(f"jni/{abi}/libudfread.so") == payload
            assert archive.read("classes.jar") == b"classes"
            assert archive.read("jni/arm64-v8a/libmpv.so") == b"mpv-arm64-v8a"

        # Running the patcher twice must not duplicate ZIP entries or mutate an
        # already-correct dependency.
        second = run_packager(aar, prefix, selected_abis)
        assert second.returncode == 0, second.stderr or second.stdout
        with zipfile.ZipFile(aar) as archive:
            names = archive.namelist()
            for abi in selected_abis:
                assert names.count(f"jni/{abi}/libudfread.so") == 1

        missing_prefix = root / "missing-prefix"
        before = aar.read_bytes()
        failure = run_packager(aar, missing_prefix, selected_abis)
        assert failure.returncode != 0, "missing per-ABI library must fail closed"
        assert "Pinned arm64-v8a libudfread.so is missing" in failure.stderr
        assert aar.read_bytes() == before, "failure must leave the original AAR untouched"

    print("MPV LGPL libudfread AAR packaging tests passed.")


if __name__ == "__main__":
    main()
