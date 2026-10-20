#!/usr/bin/env python3
"""Fixture tests for scripts/merge-mpv-lgpl-aars.py."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "merge-mpv-lgpl-aars.py"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
ABI_CSV = ",".join(ABIS)


def write_single_abi_aar(path: Path, abi: str, manifest: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", manifest)
        archive.writestr("classes.jar", b"same-java-classes")
        archive.writestr(f"jni/{abi}/libmpv.so", f"mpv-{abi}".encode())
        archive.writestr(f"jni/{abi}/libavcodec.so", f"codec-{abi}".encode())


def populate(root: Path, bad_abi: str | None = None) -> None:
    for abi in ABIS:
        manifest = b"same-manifest"
        if abi == bad_abi:
            manifest = b"different-manifest"
        directory = root / f"matrix-{abi}"
        write_single_abi_aar(directory / f"mpvlib-{abi}.aar", abi, manifest)
        write_single_abi_aar(
            directory / f"mpvlib-no-vulkun-{abi}.aar", abi, manifest
        )
        (directory / f"SHA256SUMS-{abi}.txt").write_text(
            f"fixture checksum for {abi}\n", encoding="utf-8"
        )


def run_merge(artifacts: Path, output: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), str(artifacts), str(output), "--abis", ABI_CSV],
        capture_output=True,
        text=True,
        check=False,
    )


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="mpv-lgpl-merge-test-") as temporary:
        root = Path(temporary)
        artifacts = root / "artifacts"
        output = root / "combined"
        populate(artifacts)
        result = run_merge(artifacts, output)
        assert result.returncode == 0, result.stderr or result.stdout
        for profile in ("mpvlib.aar", "mpvlib-no-vulkun.aar"):
            archive_path = output / profile
            assert archive_path.is_file(), f"missing {archive_path}"
            with zipfile.ZipFile(archive_path) as archive:
                assert archive.testzip() is None
                names = set(archive.namelist())
                found = {
                    name.split("/")[1]
                    for name in names
                    if name.startswith("jni/") and len(name.split("/")) >= 3
                }
                assert found == set(ABIS), f"{profile}: got ABI set {found}"
                assert archive.read("AndroidManifest.xml") == b"same-manifest"
                assert archive.read("classes.jar") == b"same-java-classes"
                for abi in ABIS:
                    assert f"jni/{abi}/libmpv.so" in names
                    assert f"jni/{abi}/libavcodec.so" in names

        mismatched_artifacts = root / "mismatched-artifacts"
        populate(mismatched_artifacts, bad_abi="x86_64")
        mismatch_result = run_merge(mismatched_artifacts, root / "should-not-exist")
        assert mismatch_result.returncode != 0, "metadata mismatch must fail closed"
        assert "Common AAR metadata differs" in mismatch_result.stderr

    print("MPV LGPL AAR matrix merger tests passed.")


if __name__ == "__main__":
    main()
