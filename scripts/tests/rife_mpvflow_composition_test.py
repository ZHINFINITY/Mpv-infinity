#!/usr/bin/env python3
"""Guard composition of RIFE and MPVFlow in the Standard arm64 debug runtime."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
RIFE_PREPARE_PATH = ROOT / "scripts/prepare-rife-mpv.py"
RIFE_PREPARE_SPEC = importlib.util.spec_from_file_location("prepare_rife_mpv", RIFE_PREPARE_PATH)
assert RIFE_PREPARE_SPEC is not None and RIFE_PREPARE_SPEC.loader is not None
RIFE_PREPARE = importlib.util.module_from_spec(RIFE_PREPARE_SPEC)
sys.modules[RIFE_PREPARE_SPEC.name] = RIFE_PREPARE
RIFE_PREPARE_SPEC.loader.exec_module(RIFE_PREPARE)


def test_player_dependency_composition() -> None:
    base = "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv"
    flow = base + " placebo"
    rife = base + " rife_vfi"
    combined = base + " rife_vfi placebo"
    assert RIFE_PREPARE.register_rife_player_dependency(f"{base}\n") == f"{rife}\n"
    assert RIFE_PREPARE.register_rife_player_dependency(f"{flow}\n") == f"{combined}\n"
    assert RIFE_PREPARE.register_rife_player_dependency(f"{rife}\n") == f"{rife}\n"
    assert RIFE_PREPARE.register_rife_player_dependency(f"{combined}\n") == f"{combined}\n"
    try:
        RIFE_PREPARE.register_rife_player_dependency("LOCAL_SHARED_LIBRARIES := changed\n")
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected MPV dependency changes must fail closed")


def test_standard_workflow_includes_both_runtimes() -> None:
    builder = (ROOT / "scripts/build-mpvflow-mpv-runtime.sh").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/debug-apk.yml").read_text(encoding="utf-8")
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

    assert 'MPVFLOW_INCLUDE_RIFE="${MPVFLOW_INCLUDE_RIFE:-0}"' in builder
    assert '"$MPVFLOW_INCLUDE_RIFE" == "1"' in builder
    assert builder.index("./scripts/build-rife-native.sh") < builder.index("scripts/prepare-rife-mpv.py")
    assert builder.index("scripts/prepare-rife-mpv.py") < builder.index("./include/ci.sh build")

    expected_condition = "inputs.flavor == 'standard' && inputs.target_abi == 'arm64-v8a'"
    assert workflow.count(expected_condition) >= 5, "combined runtime steps must be arm64 Standard-only"
    assert "MPVFLOW_INCLUDE_RIFE: '1'" in workflow
    assert "package-mpv-runtime-aar.py --runtime-apk" in workflow
    overlay_command = workflow.split("package-mpv-runtime-aar.py --runtime-apk", 1)[1].split("\n", 1)[0]
    assert "--without-rife" not in overlay_command, "the combined artifact must retain the RIFE library"
    verify_step = workflow.split("- name: Verify combined LGPL-only MPV runtime", 1)[1].split("- name:", 1)[0]
    assert "--abis arm64-v8a,armeabi-v7a,x86,x86_64 --require-vulkan" in verify_step
    assert "--only-selected-abis" not in verify_step, "the overlay preserves other ABI entries in the Standard AAR"
    assert "-PrifeNativeBuild=true -PmpvFlowMpvBuild=true" in workflow
    assert workflow.index("- name: Run focused interpolation regression tests") < workflow.index(
        "- name: Build debug APK"
    )
    assert "val includeRifeNative = requestedRifeNative && activeAbis == listOf(\"arm64-v8a\")" in gradle
    assert "val includeMpvFlowMpvRuntime = requestedMpvFlowMpvRuntime && activeAbis == listOf(\"arm64-v8a\")" in gradle


def main() -> None:
    test_player_dependency_composition()
    test_standard_workflow_includes_both_runtimes()
    print("RIFE + MPVFlow combined-runtime and Standard arm64 workflow tests passed.")


if __name__ == "__main__":
    main()
