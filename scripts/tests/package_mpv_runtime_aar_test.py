#!/usr/bin/env python3
"""Verify Flow-only MPV runtime overlays preserve required libraries and remove stale RIFE."""
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
    MODULE.needed_libraries = lambda path: {"libudfread.so"} if path.name.endswith("libmpv.so") else set()
    MODULE.elf_version_tags = lambda _path: set()
    MODULE.dynamic_defined_symbols = lambda _path: {
        "pl_vulkan_default_params",
        "pl_vulkan_create",
        "pl_vulkan_destroy",
        "pl_vulkan_wrap",
        "pl_vulkan_hold_ex",
        "pl_vulkan_release_ex",
    }


def runtime_verifier_command() -> None:
    aar = Path("runtime.aar")
    verifier = SCRIPT.with_name("verify-media3-dca-runtime.py")
    with patch.object(MODULE.subprocess, "run") as run:
        MODULE.verify_media3_runtime(aar, ("arm64-v8a", "x86_64"))
    assert run.call_args.args[0] == [
        sys.executable,
        str(verifier),
        str(aar),
        "--abis",
        "arm64-v8a",
    ]
    assert run.call_args.kwargs == {"check": True}
    with patch.object(MODULE.subprocess, "run") as run:
        MODULE.verify_media3_runtime(aar, ("x86_64",))
    run.assert_not_called()


def overlay_case(*, omit_udfread: bool = False) -> None:
    abi = "x86_64"
    runtime_libs = sorted(MODULE.REQUIRED_LIBS)
    if omit_udfread:
        runtime_libs.remove("libudfread.so")
    runtime_blobs = {name: f"new-runtime:{name}".encode() for name in runtime_libs}
    player_blob = b"app-specific-player-bridge"
    other_abi_player_blob = b"other-abi-player-bridge"
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
            aar.writestr("jni/arm64-v8a/libplayer.so", other_abi_player_blob)
            # A stale RIFE binary must be pruned even when its ABI is not overlaid.
            aar.writestr(f"jni/{abi}/librife_vfi.so", b"old-rife-selected-abi")
            aar.writestr("jni/arm64-v8a/librife_vfi.so", b"old-rife-other-abi")
            for name in runtime_libs:
                if name != missing_from_old_aar:
                    aar.writestr(f"jni/{abi}/{name}", f"old:{name}".encode())

        patch_runtime_validation()
        verification_calls: list[tuple[Path, tuple[str, ...]]] = []
        MODULE.verify_media3_runtime = lambda path, abis: verification_calls.append((path, abis))
        argv = [
            str(SCRIPT),
            "--runtime-apk",
            str(runtime_apk),
            "--mpv-aar",
            str(mpv_aar),
            "--abis",
            abi,
        ]
        with patch.object(sys, "argv", argv):
            if omit_udfread:
                try:
                    MODULE.main()
                except SystemExit as error:
                    assert "libudfread.so" in str(error)
                    assert "missing required native libraries" in str(error)
                    assert verification_calls == []
                    return
                raise AssertionError("runtime overlay accepted an APK without required libudfread.so")
            MODULE.main()
            assert len(verification_calls) == 1
            verified_path, verified_abis = verification_calls[0]
            assert verified_path.suffix == ".tmp"
            assert verified_abis == (abi,)

        with zipfile.ZipFile(mpv_aar) as result:
            assert result.testzip() is None
            assert result.read("AndroidManifest.xml") == b"fixture-manifest"
            assert result.read(f"jni/{abi}/libplayer.so") == player_blob
            assert result.read("jni/arm64-v8a/libplayer.so") == other_abi_player_blob
            assert result.read(f"jni/{abi}/{missing_from_old_aar}") == runtime_blobs[missing_from_old_aar]
            for name in runtime_libs:
                assert result.read(f"jni/{abi}/{name}") == runtime_blobs[name]
            assert not any(name.endswith("/librife_vfi.so") for name in result.namelist())


def main() -> None:
    assert "librife_vfi.so" not in MODULE.REQUIRED_LIBS
    assert "libudfread.so" in MODULE.REQUIRED_LIBS
    runtime_verifier_command()
    overlay_case(omit_udfread=True)
    overlay_case()
    print("MPV runtime AAR overlay tests passed (Flow-only closure and stale-RIFE pruning).")


if __name__ == "__main__":
    main()
