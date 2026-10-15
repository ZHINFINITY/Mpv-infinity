#!/usr/bin/env python3
"""Guard the MPVFlow-only app, Standard arm64 runtime, and APK packaging path."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_standard_workflow_is_flow_only() -> None:
    builder = (ROOT / "scripts/build-mpvflow-mpv-runtime.sh").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/debug-apk.yml").read_text(encoding="utf-8")
    flow_workflow = (ROOT / ".github/workflows/mpvflow-debug-arm64.yml").read_text(encoding="utf-8")
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

    assert "MPVFLOW_INCLUDE_RIFE" not in builder
    assert "build-rife-native.sh" not in builder
    assert "prepare-rife-mpv.py" not in builder
    assert "MPVFLOW_MPV_BUILD_CORES" in builder

    expected_condition = "inputs.flavor == 'standard' && inputs.target_abi == 'arm64-v8a'"
    assert workflow.count(expected_condition) >= 5, "Flow runtime steps must remain arm64 Standard-only"
    assert "rife-project" not in workflow.lower()
    assert "RIFE_BUILD_CORES" not in workflow
    assert "MPVFLOW_INCLUDE_RIFE" not in workflow

    build_runtime_step = workflow.split("- name: Build Flow-only MPVFlow arm64 runtime", 1)[1].split("- name:", 1)[0]
    assert "./scripts/build-mpvflow-mpv-runtime.sh" in build_runtime_step
    assert "MPVFLOW_MPV_BUILD_CORES: '2'" in build_runtime_step

    overlay_step = workflow.split("- name: Overlay Flow-only runtime into Standard MPV AAR", 1)[1].split("- name:", 1)[0]
    assert "package-mpv-runtime-aar.py --runtime-apk" in overlay_step
    assert "--without-rife" not in overlay_step

    verify_runtime_step = workflow.split("- name: Verify Flow-only LGPL MPV runtime", 1)[1].split("- name:", 1)[0]
    assert "--abis arm64-v8a,armeabi-v7a,x86,x86_64 --require-vulkan" in verify_runtime_step
    assert "--only-selected-abis" not in verify_runtime_step

    focused_tests = workflow.split("- name: Run focused interpolation regression tests", 1)[1].split("- name:", 1)[0]
    assert "scripts/tests/mpvflow_flow_only_test.py" in focused_tests
    assert "scripts/tests/package_mpv_runtime_aar_test.py" in focused_tests
    assert "scripts/tests/mpvflow_native_build_test.py" in focused_tests

    unit_tests = workflow.split("- name: Run Standard arm64 debug unit tests", 1)[1].split("- name:", 1)[0]
    assert "-PmpvFlowMpvBuild=true" in unit_tests
    assert "rifeNativeBuild" not in unit_tests
    build_apk = workflow.split("- name: Build debug APK", 1)[1].split("- name:", 1)[0]
    assert "-PmpvFlowMpvBuild=true" in build_apk
    assert "rifeNativeBuild" not in build_apk

    apk_check = workflow.split("- name: Verify Flow-only arm64 APK contents", 1)[1].split("- name:", 1)[0]
    assert "unzip -Z1" in apk_check
    assert "for library in libmpv.so libplayer.so libplacebo.so libudfread.so" in apk_check
    assert 'grep -Fxq "lib/arm64-v8a/$library"' in apk_check
    assert "librife_vfi\\.so" in apk_check
    assert "exit 1" in apk_check
    assert workflow.index("- name: Run focused interpolation regression tests") < workflow.index(
        "- name: Build debug APK"
    )
    assert workflow.index("- name: Verify Flow-only arm64 APK contents") < workflow.index(
        "- name: Upload debug APKs"
    )

    assert '"librife_vfi.so"' not in gradle
    assert "rifeNativeBuild" not in gradle
    assert 'buildConfigField("boolean", "MPV_HAS_RIFE"' not in gradle
    assert 'val includeMpvFlowMpvRuntime = requestedMpvFlowMpvRuntime && activeAbis == listOf("arm64-v8a")' in gradle
    assert "--without-rife" not in flow_workflow
    assert "-PrifeNativeBuild" not in flow_workflow

    # RIFE had a separate APK-producing workflow and app implementation. They must stay removed.
    removed_paths = (
        ".github/workflows/catalog-debug-arm64.yml",
        "scripts/build-rife-mpv-runtime.sh",
        "scripts/build-rife-native.sh",
        "scripts/prepare-rife-mpv.py",
        "scripts/stage-rife-model.py",
        "app/src/main/java/app/infinity/mpvz/ui/player/RifeModelInstaller.kt",
        "app/src/main/cpp/rife/vf_rife.c",
        "app/src/main/cpp/rife/rife_vfi_bridge.cpp",
        "app/src/main/cpp/rife/rife_vfi.h",
    )
    assert all(not (ROOT / path).exists() for path in removed_paths)
    app_source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "app/src/main").rglob("*")
        if path.is_file() and path.suffix in {".kt", ".java", ".c", ".cpp", ".h", ".xml"}
    ).lower()
    assert "mpv_has_rife" not in app_source
    assert "rife_frame_interpolation" not in app_source
    assert "rife_model_installer" not in app_source
    assert "pref_decoder_rife_" not in app_source


def main() -> None:
    test_standard_workflow_is_flow_only()
    print("Flow-only MPVFlow Standard arm64 workflow tests passed.")


if __name__ == "__main__":
    main()
