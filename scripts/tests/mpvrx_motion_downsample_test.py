#!/usr/bin/env python3
"""Focused tests for the MPVRx-pinned analysis downsample candidate."""
from __future__ import annotations

import argparse
import hashlib
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from patch_fongmi_motion_downsample import (  # noqa: E402
    MARKER,
    PATCHED_BLOCK,
    UPSTREAM_MOTION_SHA256,
    apply_patch,
    has_antialias_filter,
    transform_source,
)

PATCHED_MOTION_SHA256 = "db13147fd268ebe857e235a2ad066c0d34d9c35cab66f6fa93ed5767d61219a2"


def tap_count(scale: float) -> int:
    return min(4, max(1, math.ceil(scale / 2.0)))


def sample_linear(values: list[float], position: float) -> float:
    position = min(max(position, 0.0), len(values) - 1.0)
    lo = math.floor(position)
    hi = min(lo + 1, len(values) - 1)
    fraction = position - lo
    return values[lo] * (1.0 - fraction) + values[hi] * fraction


def center_sample(values: list[float], scale: float) -> float:
    return sample_linear(values, (scale - 1.0) / 2.0)


def area_sample(values: list[float], scale: float) -> float:
    taps = tap_count(scale)
    center = (scale - 1.0) / 2.0
    positions = [center + ((index + 0.5) / taps - 0.5) * scale for index in range(taps)]
    return sum(sample_linear(values, position) for position in positions) / taps


def check_shader_fixture() -> None:
    fixture = """static void downsample_fixture(void) {
            vec2 p = (vec2(gl_GlobalInvocationID.xy) + 0.5) * source_scale - 0.5;
            vec3 sampled = sample_color(source, p);
            color = vec4(linear_hdr != 0 ? hdr_proxy(sampled) : sampled, 1.0);
        }
"""
    patched, changed = transform_source(fixture, verify_upstream_hash=False)
    assert changed
    assert has_antialias_filter(patched)
    assert patched.count(PATCHED_BLOCK) == 1
    assert MARKER in patched
    repeated, changed_again = transform_source(patched, verify_upstream_hash=False)
    assert not changed_again and repeated == patched
    for malformed in (
        patched.replace("sampled /= tap_count.x * tap_count.y;", "sampled /= 0.0;"),
        patched.replace(MARKER, MARKER + "_BROKEN"),
    ):
        try:
            transform_source(malformed, verify_upstream_hash=False)
        except ValueError:
            pass
        else:
            raise AssertionError("partial or modified antialias patch was accepted")


def check_phase_response() -> None:
    assert [tap_count(scale) for scale in (1.0, 1.5, 2.0, 2.67, 4.0, 8.0)] == [1, 1, 1, 2, 2, 4]
    # A 4x source-to-analysis reduction gets 2x2 taps. A moving one-pixel line
    # at any phase within the 4px footprint no longer vanishes at the center.
    line_responses = []
    for line in range(4):
        values = [float(index == line) for index in range(4)]
        line_responses.append(area_sample(values, 4.0))
    assert all(math.isclose(value, 0.25, abs_tol=1e-9) for value in line_responses)
    center_responses = [center_sample([float(i == line) for i in range(4)], 4.0) for line in range(4)]
    assert center_responses == [0.0, 0.5, 0.5, 0.0]
    # Constant fields and linear ramps retain their mean at 4x reduction.
    assert math.isclose(area_sample([0.37] * 4, 4.0), 0.37, abs_tol=1e-9)
    assert math.isclose(area_sample([0.0, 1.0, 2.0, 3.0], 4.0), 1.5, abs_tol=1e-9)
    # The later pyramid levels are 2x and therefore retain one center sample.
    assert tap_count(2.0) == 1


def check_glsl_compile() -> None:
    validator = shutil.which("glslangValidator")
    if validator is None:
        print("Skipping GLSL compile check: glslangValidator is unavailable")
        return
    shader = f"""#version 450
layout(local_size_x = 8, local_size_y = 8) in;
layout(set = 0, binding = 0) uniform sampler2D source;
layout(set = 0, binding = 1, rgba32f) uniform writeonly image2D target;
layout(push_constant) uniform Params {{ vec2 source_scale; int linear_hdr; }} params;
vec3 sample_color(sampler2D image, vec2 p) {{
    vec2 extent = vec2(textureSize(image, 0));
    vec2 uv = (clamp(p, vec2(0.0), extent - 1.0) + 0.5) / extent;
    return textureLod(image, uv, 0.0).rgb;
}}
vec3 hdr_proxy(vec3 value) {{ return value; }}
void main() {{
    vec2 source_scale = params.source_scale;
    int linear_hdr = params.linear_hdr;
    vec4 color;
{PATCHED_BLOCK}
    imageStore(target, ivec2(gl_GlobalInvocationID.xy), color);
}}
"""
    with tempfile.TemporaryDirectory(prefix="mpvrx-downsample-glsl-") as tmp:
        shader_path = Path(tmp) / "downsample.comp"
        output_path = Path(tmp) / "downsample.spv"
        shader_path.write_text(shader, encoding="utf-8")
        result = subprocess.run(
            [validator, "-V", "--target-env", "vulkan1.1", "-o", str(output_path), str(shader_path)],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(f"glslang rejected the patched tap loop:\n{result.stdout}\n{result.stderr}")
        assert output_path.is_file() and output_path.stat().st_size > 0


def check_pinned_source(mpv_dir: Path) -> None:
    motion_path = mpv_dir / "video/out/gpu_next/motion.c"
    source = motion_path.read_text(encoding="utf-8")
    actual = hashlib.sha256(source.encode("utf-8")).hexdigest()
    assert actual == UPSTREAM_MOTION_SHA256, f"unexpected pinned motion.c hash: {actual}"
    patched, changed = transform_source(source)
    assert changed and has_antialias_filter(patched)
    patched_hash = hashlib.sha256(patched.encode("utf-8")).hexdigest()
    assert patched_hash == PATCHED_MOTION_SHA256, f"unexpected patched motion.c hash: {patched_hash}"
    assert patched.count(PATCHED_BLOCK) == 1
    assert patched.count("mp_motion_create(p->gpu, 480);") == source.count("mp_motion_create(p->gpu, 480);")
    with tempfile.TemporaryDirectory(prefix="mpvrx-downsample-optin-") as tmp:
        candidate = Path(tmp) / "motion.c"
        candidate.write_text(source, encoding="utf-8")
        assert not apply_patch(candidate, enabled=False)
        assert candidate.read_text(encoding="utf-8") == source
        assert apply_patch(candidate, enabled=True)
        assert hashlib.sha256(candidate.read_bytes()).hexdigest() == PATCHED_MOTION_SHA256
        assert not apply_patch(candidate, enabled=True)
        try:
            apply_patch(candidate, enabled=False)
        except ValueError:
            pass
        else:
            raise AssertionError("patched shader was accepted without the test-workflow opt-in")
    print(f"Pinned MPVRx source transform verified: {actual} -> {patched_hash}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", type=Path)
    args = parser.parse_args()
    check_shader_fixture()
    check_phase_response()
    check_glsl_compile()
    if args.mpv_dir:
        check_pinned_source(args.mpv_dir)
    print("MPVRx downsample candidate passed bounded-tap, phase-stability, ramp/DC, shader-compile, idempotence, and source guards")


if __name__ == "__main__":
    main()
