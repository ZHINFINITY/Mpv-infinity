#!/usr/bin/env python3
"""Guard Android MPV subprocess fallback and FongMi MEMC runtime wiring."""
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

    def test_fongmi_interpolation_code_and_target_fps_option_remain_registered(self):
        prepare_source = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
        mpv_view = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
        self.assertNotIn("FONGMI_PATCH", prepare_source)
        self.assertIn('"video/out/gpu_next/motion.c"', prepare_source)
        self.assertIn('motion_option_marker = \'{"motion-interpolation", OPT_INT(motion_fps), M_RANGE(0, 240)}\'', prepare_source)
        self.assertIn('"video/out/hwdec/hwdec_aimagereader_vk_direct.c"', prepare_source)
        self.assertIn("prefer_fork_for_android_mpv_subprocesses", prepare_source)
        self.assertIn('PlaybackSession.setOptionString("motion-interpolation", targetFps.toString())', mpv_view)

    def test_packaged_runtime_verifier_rejects_shared_spawn_but_accepts_fork_profile(self):
        path = Path("unused-libmpv.so")
        for feature in ("clone", "rfork"):
            with patch.object(verifier, "run_tool", return_value=f"List of enabled features: android {feature} vulkan"):
                with self.assertRaisesRegex(SystemExit, "shared-address subprocess"):
                    verifier.verify_mpv("arm64-v8a", path, False, False, require_fork_subprocess=True)
        with patch.object(verifier, "run_tool", return_value="List of enabled features: android vulkan"):
            verifier.verify_mpv("arm64-v8a", path, False, False, require_fork_subprocess=True)

    def test_android_glesv2_dependency_is_allowed_and_unknown_dependency_is_rejected(self):
        paths = {
            "libmpv.so": Path("arm64-libmpv.so"),
            "libudfread.so": Path("arm64-libudfread.so"),
        }

        def glesv2_dependencies(path):
            if path.name == "arm64-libmpv.so":
                return {"libudfread.so", "libGLESv2.so"}
            return {"libc.so"}

        self.assertIn("libGLESv2.so", verifier.ANDROID_SYSTEM_LIBS)
        with patch.object(verifier, "needed_libraries", side_effect=glesv2_dependencies):
            verifier.verify_dependency_closure("arm64-v8a", paths)

        def unknown_dependencies(path):
            if path.name == "arm64-libmpv.so":
                return {"libudfread.so", "libvendor-private.so"}
            return {"libc.so"}

        with patch.object(verifier, "needed_libraries", side_effect=unknown_dependencies):
            with self.assertRaisesRegex(SystemExit, "libvendor-private.so"):
                verifier.verify_dependency_closure("arm64-v8a", paths)

    def test_debug_workflow_runs_regression_and_checks_overlaid_runtime(self):
        workflow = (ROOT / ".github/workflows/debug-apk.yml").read_text(encoding="utf-8")
        self.assertIn("python3 scripts/tests/mpvflow_android_subprocess_test.py", workflow)
        self.assertIn("--require-vulkan --require-fork-subprocess", workflow)
        cache_key = next(line for line in workflow.splitlines() if "hashFiles('scripts/build-mpvflow-mpv-runtime.sh'" in line)
        self.assertIn("scripts/prepare-mpvflow.py", cache_key)


if __name__ == "__main__":
    unittest.main()
