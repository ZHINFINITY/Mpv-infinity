#!/usr/bin/env python3
"""Verify missing runtime .so files are appended to an existing MPV AAR."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/package-mpv-runtime-aar.py"
SPEC = importlib.util.spec_from_file_location("package_mpv_runtime_aar", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def main() -> None:
    abi = "x86_64"
    runtime_libs = sorted(MODULE.FLOW_REQUIRED_LIBS)
    assert "libplacebo.so" in runtime_libs
    runtime_blobs = {name: f"new-runtime:{name}".encode() for name in runtime_libs}
    player_blob = b"app-specific-player-bridge"
    missing_from_old_aar = "libplacebo.so"

    with tempfile.TemporaryDirectory(prefix="mpv-runtime-aar-overlay-test-") as td:
        root = Path(td)
        runtime_apk = root / "runtime.apk"
        mpv_aar = root / "mpvlib.aar"
        with zipfile.ZipFile(runtime_apk, "w", compression=zipfile.ZIP_DEFLATED) as apk:
            for name, blob in runtime_blobs.items():
                apk.writestr(f"lib/{abi}/{name}", blob)

        with zipfile.ZipFile(mpv_aar, "w", compression=zipfile.ZIP_DEFLATED) as aar:
            aar.writestr("AndroidManifest.xml", b"fixture-manifest")
            aar.writestr(f"jni/{abi}/libplayer.so", player_blob)
            for name in runtime_libs:
                if name != missing_from_old_aar:
                    aar.writestr(f"jni/{abi}/{name}", f"old:{name}".encode())

        MODULE.assert_abi_library = lambda _path, _abi: None
        MODULE.assert_lgpl_runtime = lambda _abi, _entries, _temp: None
        MODULE.needed_libraries = lambda _path: set()
        MODULE.elf_version_tags = lambda _path: set()
        MODULE.dynamic_defined_symbols = lambda _path: {
            "pl_vulkan_default_params",
            "pl_vulkan_create",
            "pl_vulkan_destroy",
            "pl_vulkan_wrap",
            "pl_vulkan_hold_ex",
            "pl_vulkan_release_ex",
        }

        argv = [
            str(SCRIPT),
            "--runtime-apk",
            str(runtime_apk),
            "--mpv-aar",
            str(mpv_aar),
            "--abis",
            abi,
            "--without-rife",
        ]
        with patch.object(sys, "argv", argv):
            MODULE.main()

        with zipfile.ZipFile(mpv_aar) as result:
            assert result.testzip() is None
            assert result.read("AndroidManifest.xml") == b"fixture-manifest"
            assert result.read(f"jni/{abi}/libplayer.so") == player_blob
            assert result.read(f"jni/{abi}/{missing_from_old_aar}") == runtime_blobs[
                missing_from_old_aar
            ]
            for name in runtime_libs:
                assert result.read(f"jni/{abi}/{name}") == runtime_blobs[name]

    print("MPV runtime AAR overlay missing-library test passed.")


if __name__ == "__main__":
    main()
