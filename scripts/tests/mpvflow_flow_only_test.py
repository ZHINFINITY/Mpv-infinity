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

    cache_step_name = "- name: Restore verified Flow-only MPVFlow arm64 runtime"
    legacy_key = "mpvflow-arm64-runtime-v1-8b9cdaf1e5bcc44928adae0b2f3b9f1d8808504bdc5e129ec67b3e0b4871d812"

    def runtime_cache_key(contents: str) -> str:
        restore_step = contents.split(cache_step_name, 1)[1].split("- name:", 1)[0]
        return next(line.strip()[len("key: ") :] for line in restore_step.splitlines() if line.strip().startswith("key: "))

    manual_cache_key = runtime_cache_key(workflow)
    push_cache_key = runtime_cache_key(flow_workflow)
    assert manual_cache_key == push_cache_key, "manual and push builds must share the same verified native runtime cache"
    for native_input in (
        "scripts/build-mpvflow-mpv-runtime.sh",
        "scripts/prepare-mpvflow.py",
        "scripts/patch-ffmpeg-symver.py",
        "app/src/main/cpp/**",
        "THIRD_PARTY_LICENSES/**",
        "app/src/main/assets/mpv/licenses/**",
    ):
        assert native_input in manual_cache_key, f"native cache key must include {native_input}"
    assert ".github/workflows/debug-apk.yml" not in manual_cache_key
    assert ".github/workflows/mpvflow-debug-arm64.yml" not in manual_cache_key
    for pin in (
        "mpvflow-arm64-runtime-v2-builder-1efc82739f6f7034-mpv-c1529642089bfebf-cores2-",
        "mpvflow-runtime-legacy-compat.outputs.matches_seed == 'true'",
        "mpvflow-runtime-legacy-cache.outputs.cache-hit",
    ):
        assert pin in manual_cache_key or all(pin in candidate for candidate in (workflow, flow_workflow)), (
            f"native cache behavior must keep {pin}"
        )
    for candidate in (workflow, flow_workflow):
        assert candidate.count("uses: actions/cache/restore@v4") == 2
        assert "uses: actions/cache/save@v4" in candidate
        assert "MPVFLOW_RUNTIME_APK=$apk" in candidate
        assert "steps.mpvflow-runtime-cache.outputs.cache-hit == 'true'" in candidate
        assert "steps.mpvflow-runtime-cache.outputs.cache-hit != 'true'" in candidate
        assert legacy_key in candidate
        assert "id: mpvflow-runtime-legacy-compat" in candidate
        assert "id: mpvflow-runtime-legacy-cache" in candidate
        assert "seed_sha='1dc0a03c540fc7aee19101fdcfa759e0f7aeffd1'" in candidate
        assert 'git diff --quiet "$seed_sha" HEAD --' in candidate
        assert "steps.mpvflow-runtime-legacy-compat.outputs.matches_seed == 'true'" in candidate
        assert "steps.mpvflow-runtime-legacy-cache.outputs.cache-hit == 'true'" in candidate
        assert "steps.mpvflow-runtime-legacy-cache.outputs.cache-hit != 'true'" in candidate
        for required_input in (
            "scripts/build-mpvflow-mpv-runtime.sh",
            "scripts/prepare-mpvflow.py",
            "scripts/patch-ffmpeg-symver.py",
            "app/src/main/cpp",
            "THIRD_PARTY_LICENSES",
            "app/src/main/assets/mpv/licenses",
            "ref: 1efc82739f6f7034cd63e7ee8b3ec5431df27467",
            "ref: c1529642089bfebfc928a1c1664638a7a5d219ba",
            "MPVFLOW_MPV_BUILD_CORES: '2'",
        ):
            assert required_input in candidate
        cached_runtime_step = candidate.split("- name: Use cached Flow-only MPVFlow arm64 runtime", 1)[1].split(
            "- name:", 1
        )[0]
        assert "steps.mpvflow-runtime-cache.outputs.cache-hit == 'true'" in cached_runtime_step
        assert "steps.mpvflow-runtime-legacy-cache.outputs.cache-hit == 'true'" in cached_runtime_step
    assert workflow.index("- name: Verify Flow-only LGPL MPV runtime") < workflow.index(
        "- name: Save verified Flow-only MPVFlow arm64 runtime"
    ) < workflow.index("- name: Run focused interpolation regression tests")
    assert flow_workflow.index("- name: Run MPVFlow core and GPU shader regression tests") < flow_workflow.index(
        "- name: Verify GPL-free MPVFlow arm64 runtime"
    ) < flow_workflow.index("- name: Save verified Flow-only MPVFlow arm64 runtime")

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
    assert "steps.mpvflow-runtime-cache.outputs.cache-hit != 'true'" in build_runtime_step
    assert "steps.mpvflow-runtime-legacy-cache.outputs.cache-hit != 'true'" in build_runtime_step
    assert "steps.mpvflow-runtime-cache.outputs.cache-hit != 'true' && steps.mpvflow-runtime-legacy-cache.outputs.cache-hit != 'true'" in build_runtime_step
    assert "MPVFLOW_MPV_BUILD_CORES: '2'" in build_runtime_step

    native_dependencies_step = workflow.split(
        "- name: Install native interpolation build dependencies", 1
    )[1].split("- name:", 1)[0]
    assert "inputs.flavor == 'standard' && inputs.target_abi == 'arm64-v8a'" in native_dependencies_step
    assert "steps.mpvflow-runtime-cache.outputs.cache-hit" not in native_dependencies_step
    assert "steps.mpvflow-runtime-legacy-cache.outputs.cache-hit" not in native_dependencies_step

    push_build_step = flow_workflow.split(
        "- name: Build MPVFlow-only MPV native arm64 debug package", 1
    )[1].split("- name:", 1)[0]
    assert "./scripts/build-mpvflow-mpv-runtime.sh" in push_build_step
    assert "steps.mpvflow-runtime-cache.outputs.cache-hit != 'true'" in push_build_step
    assert "steps.mpvflow-runtime-legacy-cache.outputs.cache-hit != 'true'" in push_build_step
    assert "steps.mpvflow-runtime-cache.outputs.cache-hit != 'true' && steps.mpvflow-runtime-legacy-cache.outputs.cache-hit != 'true'" in push_build_step

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
    assert 'apk_entries="$(unzip -Z1 "$apk")"' in apk_check
    assert "for library in libmpv.so libplayer.so libplacebo.so libudfread.so" in apk_check
    assert 'grep -Fx "lib/arm64-v8a/$library" <<< "$apk_entries" >/dev/null' in apk_check
    assert '| grep' not in apk_check
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


def test_mpvflow_settings_keep_single_toggle_and_target_fps() -> None:
    screen = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/preferences/DecoderPreferencesScreen.kt").read_text(
        encoding="utf-8"
    )
    strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
    prefs = (ROOT / "app/src/main/java/app/infinity/mpvz/preferences/DecoderPreferences.kt").read_text(
        encoding="utf-8"
    )
    player = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")

    assert "settingsSearchTarget(R.string.pref_decoder_mpvflow_title)" in screen
    assert "Surface(" in screen and "surfaceContainerLow" in screen
    assert "titleStyle =" in screen and "MaterialTheme.typography.titleMedium" in screen
    assert screen.count("preferences.mpvFlowFrameInterpolation.set(enabled)") == 1
    assert '<string name="pref_decoder_mpvflow_title">Frame interpolation</string>' in strings
    assert "pref_decoder_mpvflow_compute_api_" not in screen and "pref_decoder_mpvflow_compute_api_" not in strings
    assert "preferences.mpvFlowComputeApi" not in screen
    assert "val mpvFlowTargetFps by preferences.mpvFlowTargetFps.collectAsState()" in screen
    assert "onValueChange = preferences.mpvFlowTargetFps::set" in screen
    assert "values = MPVFLOW_TARGET_FPS_OPTIONS" in screen
    assert "preferences.mpvFlowFrameInterpolation.set(enabled)" in screen
    assert "if (it >= 90) R.string.pref_decoder_mpvflow_target_fps_warning_value" in screen
    assert '<string name="pref_decoder_mpvflow_target_fps_title">MPVFlow target frame rate</string>' in strings
    assert '<string name="pref_decoder_mpvflow_target_fps_value">%1$d fps</string>' in strings
    assert '<string name="pref_decoder_mpvflow_target_fps_warning_value">(!) %1$d fps</string>' in strings
    assert "internal val MPVFLOW_TARGET_FPS_OPTIONS = listOf(48, 60, 72, 90, 96, 120, 144)" in prefs
    assert 'mpvFlowComputeApi = preferenceStore.getEnum("mpvflow_compute_api", MpvFlowComputeApi.AUTO)' in prefs
    assert 'mpvFlowTargetFps = preferenceStore.getInt("mpvflow_target_fps", DEFAULT_MPVFLOW_TARGET_FPS)' in prefs
    assert "decoderPreferences.mpvFlowComputeApi.get()" in player
    assert "decoderPreferences.mpvFlowTargetFps.get()" in player


def main() -> None:
    test_standard_workflow_is_flow_only()
    test_mpvflow_settings_keep_single_toggle_and_target_fps()
    print("Flow-only MPVFlow Standard arm64 workflow tests passed.")


if __name__ == "__main__":
    main()
