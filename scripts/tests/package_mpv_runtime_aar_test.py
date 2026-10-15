#!/usr/bin/env python3
"""Verify MPV runtime overlays preserve the selected interpolation libraries."""
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


def patch_runtime_validation() -> None:
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


def overlay_case(*, include_rife: bool) -> None:
    abi = "x86_64"
    runtime_libs = sorted(MODULE.REQUIRED_LIBS if include_rife else MODULE.FLOW_REQUIRED_LIBS)
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
            # A Flow-only overlay must remove a stale RIFE entry; the combined
            # overlay must replace/retain it as part of the runtime package.
            aar.writestr(f"jni/{abi}/librife_vfi.so", b"old-rife")
            for name in runtime_libs:
                if name not in {missing_from_old_aar, "librife_vfi.so"}:
                    aar.writestr(f"jni/{abi}/{name}", f"old:{name}".encode())

        patch_runtime_validation()
        argv = [
            str(SCRIPT),
            "--runtime-apk",
            str(runtime_apk),
            "--mpv-aar",
            str(mpv_aar),
            "--abis",
            abi,
        ]
        if not include_rife:
            argv.append("--without-rife")
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
            if include_rife:
                assert result.read(f"jni/{abi}/librife_vfi.so") == runtime_blobs["librife_vfi.so"]
            else:
                assert f"jni/{abi}/librife_vfi.so" not in result.namelist()


def main() -> None:
    assert "librife_vfi.so" in MODULE.REQUIRED_LIBS
    assert "librife_vfi.so" not in MODULE.FLOW_REQUIRED_LIBS
    overlay_case(include_rife=False)
    overlay_case(include_rife=True)
    print("MPV runtime AAR overlay tests passed (Flow-only removal and combined RIFE retention).")


if __name__ == "__main__":
    main()
