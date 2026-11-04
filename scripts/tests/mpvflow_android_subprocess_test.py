#!/usr/bin/env python3
"""Guard Android MPVFlow's yt-dlp subprocess fallback and interpolation path."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise AssertionError(f"Could not load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load_module("prepare_mpvflow", ROOT / "scripts/prepare-mpvflow.py")
verifier = load_module("verify_mpv_aar", ROOT / "scripts/verify-mpv-aar-lgpl.py")
CLONE_LINE = "features += {'clone': cc.has_header_symbol('sched.h', 'clone', args: '-D_GNU_SOURCE')}"
ANDROID_FORK_LINE = "features += {'clone': host_machine.system() != 'android' and cc.has_header_symbol('sched.h', 'clone', args: '-D_GNU_SOURCE')}"


class AndroidSubprocessFallbackTest(unittest.TestCase):
    def test_only_android_clone_detection_changes_and_is_idempotent(self):
        fixture = (
            CLONE_LINE
            + "\n# MPVFLOW_ANDROID_GPU\nsources += files('video/out/mpvflow_gpu.c')\n"
        )
        patched = prepare.prefer_fork_for_android_mpv_subprocesses(fixture)
        self.assertEqual(patched, fixture.replace(CLONE_LINE, ANDROID_FORK_LINE, 1))
        self.assertEqual(prepare.prefer_fork_for_android_mpv_subprocesses(patched), patched)
        self.assertIn("# MPVFLOW_ANDROID_GPU", patched)
        self.assertIn("video/out/mpvflow_gpu.c", patched)

    def test_unknown_or_duplicate_pinned_clone_detection_fails_closed(self):
        with self.assertRaises(SystemExit):
            prepare.prefer_fork_for_android_mpv_subprocesses("features += {'clone': true}\n")
        with self.assertRaises(SystemExit):
            prepare.prefer_fork_for_android_mpv_subprocesses(CLONE_LINE + "\n" + CLONE_LINE)

    def test_flow_interpolation_code_and_target_fps_option_remain_registered(self):
        prepare_source = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
        vo_patch = (ROOT / "app/src/main/cpp/mpvflow/mpvflow-vo-gpu-next.patch").read_text(encoding="utf-8")
        mpv_view = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
        self.assertIn('gpu_source = "video/out/mpvflow_gpu.c"', prepare_source)
        self.assertIn('meson_marker = "# MPVFLOW_ANDROID_GPU"', prepare_source)
        self.assertIn("MPVFLOW_GPU_INTEGRATION", vo_patch)
        self.assertIn("flow_render_frame", vo_patch)
        self.assertIn('"mpvflow-target-fps", OPT_INT(mpvflow_target_fps)', vo_patch)
        self.assertIn('PlaybackSession.setOptionString("mpvflow-target-fps", mpvFlowTargetFps.toString())', mpv_view)

    def test_packaged_runtime_verifier_rejects_shared_spawn_but_accepts_fork_profile(self):
        path = Path("unused-libmpv.so")
        for feature in ("clone", "rfork"):
            with patch.object(verifier, "run_tool", return_value=f"List of enabled features: android {feature} vulkan"):
                with self.assertRaisesRegex(SystemExit, "shared-address subprocess"):
                    verifier.verify_mpv("arm64-v8a", path, False, False, require_fork_subprocess=True)
        with patch.object(verifier, "run_tool", return_value="List of enabled features: android vulkan"):
            verifier.verify_mpv("arm64-v8a", path, False, False, require_fork_subprocess=True)

    def test_debug_workflow_runs_regression_and_checks_overlaid_runtime(self):
        workflow = (ROOT / ".github/workflows/debug-apk.yml").read_text(encoding="utf-8")
        self.assertIn("python3 scripts/tests/mpvflow_android_subprocess_test.py", workflow)
        self.assertIn("--require-vulkan --require-fork-subprocess", workflow)
        cache_key = next(line for line in workflow.splitlines() if "hashFiles('scripts/build-mpvflow-mpv-runtime.sh'" in line)
        self.assertIn("scripts/prepare-mpvflow.py", cache_key)


if __name__ == "__main__":
    unittest.main()
