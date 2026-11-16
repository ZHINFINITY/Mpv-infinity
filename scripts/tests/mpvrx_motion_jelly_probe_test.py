#!/usr/bin/env python3
"""Contract tests for the opt-in MPVRx Jelly motion probe transformer."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
TESTS = ROOT / "scripts/tests"
sys.path.insert(0, str(SCRIPTS))
sys.path.insert(0, str(TESTS))

from patch_fongmi_motion_downsample import transform_source as transform_aa  # noqa: E402
from patch_fongmi_motion_jelly import transform_source as transform_jelly  # noqa: E402
from patch_fongmi_motion_jelly_probe import (  # noqa: E402
    COUNT,
    JELLY_SHA256,
    MARKER,
    RECORD_BYTES,
    transform_header,
    transform_motion,
)
from mpvrx_motion_source import (  # noqa: E402
    MOTION_HEADER_JELLY_PROBE_SHA256,
    MOTION_JELLY_PROBE_SHA256,
)
from mpvrx_motion_jelly_test import extract_glsl  # noqa: E402

prepare_spec = importlib.util.spec_from_file_location("prepare_mpvflow", SCRIPTS / "prepare-mpvflow.py")
assert prepare_spec and prepare_spec.loader
prepare_module = importlib.util.module_from_spec(prepare_spec)
prepare_spec.loader.exec_module(prepare_module)
apply_motion_jelly_experimental_patch = prepare_module.apply_motion_jelly_experimental_patch

MPV_DIR = Path(os.environ.get("MPVRX_JELLY_PROBE_MPV_DIR", "/workspace/mpvrx-pinned-mpv-95ec8e9"))
MOTION_C = MPV_DIR / "video/out/gpu_next/motion.c"
MOTION_H = MPV_DIR / "video/out/gpu_next/motion.h"
REQUIRE_GLSLANG = False


def jelly_sources() -> tuple[str, str]:
    source, _ = transform_aa(MOTION_C.read_text(encoding="utf-8"))
    source, _ = transform_jelly(source)
    return source, MOTION_H.read_text(encoding="utf-8")


def compile_glsl(source: str, *, probe: bool, required: bool | None = None) -> None:
    if required is None:
        required = REQUIRE_GLSLANG
    validator = shutil.which("glslangValidator")
    if validator is None:
        if required:
            raise AssertionError("glslangValidator is required for probe shader validation")
        return

    sample = extract_glsl(source, "sample_header")
    header_name = "synthesis_probe_header" if probe else "synthesis_pixel_header"
    body_name = "synthesis_probe_body" if probe else "synthesis_body"
    header = extract_glsl(source, header_name)
    body = extract_glsl(source, body_name)
    wrapper = """#version 450
layout(local_size_x = 1, local_size_y = 1) in;
layout(set = 0, binding = 0) uniform sampler2D first;
layout(set = 0, binding = 1) uniform sampler2D second;
layout(set = 0, binding = 2) uniform sampler2D forward;
layout(set = 0, binding = 3) uniform sampler2D backward;
layout(set = 0, binding = 4) uniform sampler2D scene;
"""
    if probe:
        wrapper += """layout(std430, set = 0, binding = 5) buffer Probe { vec4 probe_records[64]; };
layout(std430, set = 0, binding = 6) buffer Result { vec4 result_color; };
layout(push_constant) uniform Params {
    vec2 output_size;
    vec2 motion_size;
    vec2 projection_ratio;
    float alpha;
    int linear_hdr;
    int probe_enabled;
    ivec2 probe_pixel;
    uvec4 probe_id_halves;
    vec4 probe_metadata[5];
} params;
#define probe_enabled params.probe_enabled
#define probe_pixel params.probe_pixel
#define probe_id_halves params.probe_id_halves
#define probe_metadata params.probe_metadata
"""
    else:
        wrapper += """layout(std430, set = 0, binding = 5) buffer Result { vec4 result_color; };
layout(push_constant) uniform Params {
    vec2 output_size;
    vec2 motion_size;
    vec2 projection_ratio;
    float alpha;
    int linear_hdr;
} params;
"""
    wrapper += """#define output_size params.output_size
#define motion_size params.motion_size
#define projection_ratio params.projection_ratio
#define alpha params.alpha
#define linear_hdr params.linear_hdr
"""
    main = "void main() {\n    vec4 color;\n" + body + "\n    result_color = color;\n}\n"
    shader = wrapper + sample + "\n" + header + "\n" + main
    with tempfile.TemporaryDirectory(prefix="mpvrx-jelly-probe-glsl-") as temp:
        shader_path = Path(temp) / ("probe.comp" if probe else "baseline.comp")
        output_path = shader_path.with_suffix(".spv")
        shader_path.write_text(shader, encoding="utf-8")
        result = subprocess.run(
            [validator, "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(output_path), str(shader_path)],
            capture_output=True,
            text=True,
        )
        if result.returncode:
            raise AssertionError("GLSL compile failed:\n" + result.stdout + result.stderr)
        if not output_path.is_file() or output_path.stat().st_size == 0:
            raise AssertionError("glslangValidator produced an empty SPIR-V module")


class MotionProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.baseline, cls.header = jelly_sources()
        cls.probe, cls.changed = transform_motion(cls.baseline)
        cls.header_probe, cls.header_changed = transform_header(cls.header)

    def test_exact_jelly_source_hash(self):
        self.assertEqual(hashlib.sha256(self.baseline.encode()).hexdigest(), JELLY_SHA256)
        self.assertEqual(hashlib.sha256(self.probe.encode()).hexdigest(), MOTION_JELLY_PROBE_SHA256)
        self.assertEqual(hashlib.sha256(self.header_probe.encode()).hexdigest(), MOTION_HEADER_JELLY_PROBE_SHA256)

    def test_transform_idempotence_and_no_partial_marker(self):
        self.assertTrue(self.changed)
        self.assertTrue(self.header_changed)
        self.assertIn("synthesis_probe_header", self.probe)
        self.assertIn("mp_motion_render_frames_probe", self.probe)
        self.assertIn("mp_motion_render_views_probe", self.probe)
        same, changed = transform_motion(self.probe)
        self.assertFalse(changed)
        self.assertEqual(same, self.probe)
        same_header, changed_header = transform_header(self.header_probe)
        self.assertFalse(changed_header)
        self.assertEqual(same_header, self.header_probe)
        with self.assertRaises(ValueError):
            transform_motion("/* " + MARKER + " */")
        with self.assertRaises(ValueError):
            transform_motion("not pinned source")

    def test_repeated_preparation_accepts_only_exact_probe_source(self):
        with tempfile.TemporaryDirectory(prefix="mpvrx-jelly-prepare-repeat-") as temp:
            motion_path = Path(temp) / "motion.c"
            antialias, changed = transform_aa(MOTION_C.read_text(encoding="utf-8"))
            self.assertTrue(changed)
            motion_path.write_text(antialias, encoding="utf-8")

            self.assertTrue(apply_motion_jelly_experimental_patch(motion_path, probe_enabled=True))
            self.assertEqual(motion_path.read_text(encoding="utf-8"), self.baseline)
            probe, changed = transform_motion(motion_path.read_text(encoding="utf-8"))
            self.assertTrue(changed)
            motion_path.write_text(probe, encoding="utf-8")
            self.assertEqual(hashlib.sha256(probe.encode()).hexdigest(), MOTION_JELLY_PROBE_SHA256)

            self.assertFalse(apply_motion_jelly_experimental_patch(motion_path, probe_enabled=True))
            self.assertEqual(motion_path.read_text(encoding="utf-8"), probe)

            malformed_probe = probe.replace("MPVFLOW_JELLY_PROBE_V1", "MPVFLOW_JELLY_PROBE_V0", 1)
            motion_path.write_text(malformed_probe, encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "marker/source mismatch"):
                apply_motion_jelly_experimental_patch(motion_path, probe_enabled=True)

    def test_request_api_header_and_layout(self):
        for item in (
            "#define MP_MOTION_PROBE_RECORDS 64",
            "struct mp_motion_probe_request",
            "pl_buf buffer;",
            "uint64_t first_id, second_id",
            "mp_motion_render_frames_probe",
            "mp_motion_render_views_probe",
            "mp_motion_render_probe",
        ):
            self.assertIn(item, self.header_probe)
        self.assertEqual(COUNT * 16, RECORD_BYTES)
        writer_header = (ROOT / "app/src/main/cpp/mpvflow/mpvrx_jelly_probe_writer.h").read_text(encoding="utf-8")
        self.assertIn(f"#define MPVRX_JELLY_PROBE_RECORD_BYTES {RECORD_BYTES}u", writer_header)

    def test_probe_off_synthesis_is_byte_identical(self):
        for name in ("sample_header", "synthesis_pixel_header", "synthesis_body", "synthesis_shader_body"):
            self.assertEqual(extract_glsl(self.baseline, name), extract_glsl(self.probe, name), name)
        self.assertEqual(
            extract_glsl(self.baseline, "synthesis_pixel_header") + extract_glsl(self.baseline, "synthesis_body"),
            extract_glsl(self.probe, "synthesis_pixel_header") + extract_glsl(self.probe, "synthesis_body"),
        )
        # The final color expressions/returns remain present in the instrumented variant.
        probe_header = extract_glsl(self.probe, "synthesis_probe_header")
        self.assertIn("for (int i = 0; i < 64; i++)", probe_header)
        self.assertIn("probe_records[i] = vec4(0.0)", probe_header)
        for expression in (
            "return original;",
            "return mix(original, jelly_region, jelly_fill);",
            "jelly_region",
        ):
            self.assertIn(expression, probe_header)
        self.assertIn("vec4 sample_rgba", extract_glsl(self.probe, "sample_header"))
        self.assertIn("probe_records[58] = uintBitsToFloat(probe_id_halves)", extract_glsl(self.probe, "synthesis_probe_body"))

    def test_probe_is_explicit_and_every_existing_call_is_routed(self):
        self.assertIn("if (probe)", self.probe)
        self.assertIn("PL_DESC_BUF_STORAGE", self.probe)
        self.assertIn("probe ? synthesis_probe_header : synthesis_pixel_header", self.probe)
        self.assertIn("probe ? synthesis_probe_body : synthesis_body", self.probe)
        self.assertIn("alpha, NULL, 0, probe", self.probe)
        self.assertIn("alpha, MOTION_INPUT_VIEW, true, probe, output", self.probe)
        self.assertIn("false, NULL, output", self.probe)
        self.assertIn("render_frames(m, first, second, alpha, false, NULL, output)", self.probe)
        self.assertIn("synthesize(m, view->texture, m->images[0][0], 0, shader, 1, NULL)", self.probe)
        self.assertIn("synthesize(m, first->texture, second->texture, alpha, shader, 0, NULL)", self.probe)
        self.assertIn("mp_motion_render_frames_probe", self.probe)
        self.assertIn("mp_motion_render_probe", self.probe)

    def test_record_layout_and_glsl_compile(self):
        self.assertIn("probe_records[57]", self.probe)
        self.assertIn("probe_records[63]", self.probe)
        self.assertIn("records_var.dim_a = MP_MOTION_PROBE_RECORDS", self.probe)
        compile_glsl(self.probe, probe=False)
        compile_glsl(self.probe, probe=True)

    def test_probe_uniforms_fit_minimum_vulkan_push_constants(self):
        self.assertIn("float probe_metadata[12] = {0};", self.probe)
        self.assertIn("metadata_var.dim_a = 3;", self.probe)
        # std430 packing for probe_vars: vec2, vec2, float, int, vec2, int,
        # ivec2, vec4[3], uvec4. The final byte offset is 112, below 128.
        fields = ((8, 8), (8, 8), (4, 4), (4, 4), (8, 8), (4, 4),
                  (8, 8), (48, 16), (16, 16))
        offset = 0
        for size, alignment in fields:
            offset = (offset + alignment - 1) // alignment * alignment
            offset += size
        self.assertEqual(offset, 112)
        self.assertLessEqual(offset, 128)


def configure_cli() -> None:
    global MPV_DIR, MOTION_C, MOTION_H, REQUIRE_GLSLANG
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--mpv-dir", type=Path)
    parser.add_argument("--require-glslang", action="store_true")
    args, remaining = parser.parse_known_args()
    if args.mpv_dir:
        MPV_DIR = args.mpv_dir
        MOTION_C = MPV_DIR / "video/out/gpu_next/motion.c"
        MOTION_H = MPV_DIR / "video/out/gpu_next/motion.h"
    REQUIRE_GLSLANG = args.require_glslang
    sys.argv = [sys.argv[0], *remaining]


if __name__ == "__main__":
    configure_cli()
    unittest.main(verbosity=2)
