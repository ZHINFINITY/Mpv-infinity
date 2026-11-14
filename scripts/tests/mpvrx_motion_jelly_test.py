#!/usr/bin/env python3
"""Verify the opt-in MPVRx jelly patch and compile the exact pinned synthesis GLSL."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from mpvrx_motion_source import verify_mpv_source  # noqa: E402
from patch_fongmi_motion_downsample import (  # noqa: E402
    MARKER as DOWNSAMPLE_MARKER,
    transform_source as transform_downsample,
)
from patch_fongmi_motion_jelly import (  # noqa: E402
    MARKER as JELLY_MARKER,
    MOTION_JELLY_PREVIEW_SHA256,
    MOTION_JELLY_SHA256,
    ORIGINAL_ZERO_FLOW,
    PATCHED_ZERO_FLOW,
    PATCHED_ZERO_FLOW_PREVIEW,
    PREVIEW_MARKER,
    PREVIEW_RETURN,
    SYNTHESIS_RETURN,
    apply_patch,
    apply_preview_patch,
    has_jelly_region_patch,
    has_jelly_preview_patch,
    transform_preview_source,
    transform_source as transform_jelly,
)


def extract_glsl(source: str, name: str) -> str:
    anchor = f"static const char {name}[] = GLSL("
    if source.count(anchor) != 1:
        raise SystemExit(f"Expected one {name} GLSL block in pinned motion.c")
    start = source.index(anchor) + len(anchor)
    depth = 1
    index = start
    line_comment = False
    block_comment = False
    string = False
    escaped = False
    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""
        if line_comment:
            if char == "\n":
                line_comment = False
        elif block_comment:
            if char == "*" and next_char == "/":
                block_comment = False
                index += 1
        elif string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                string = False
        elif char == "/" and next_char == "/":
            line_comment = True
            index += 1
        elif char == "/" and next_char == "*":
            block_comment = True
            index += 1
        elif char == '"':
            string = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[start:index]
        index += 1
    raise SystemExit(f"Unterminated GLSL block {name}")


def compile_synthesis_shader(source: str, *, required: bool) -> None:
    validator = shutil.which("glslangValidator")
    if validator is None:
        if required:
            raise SystemExit("glslangValidator is required for the jelly shader regression")
        print("Skipping GLSL compile: glslangValidator is unavailable")
        return
    sample_header = extract_glsl(source, "sample_header")
    synthesis_header = extract_glsl(source, "synthesis_pixel_header")
    wrapper = """#version 450
layout(local_size_x = 8, local_size_y = 8) in;
layout(set = 0, binding = 0) uniform sampler2D source0;
layout(set = 0, binding = 1) uniform sampler2D source1;
layout(set = 0, binding = 2) uniform sampler2D warp0;
layout(set = 0, binding = 3) uniform sampler2D warp1;
layout(set = 0, binding = 4) uniform sampler2D scene_map;
layout(set = 0, binding = 5, rgba32f) uniform writeonly image2D target;
layout(push_constant) uniform Params {
    vec2 output_extent;
    vec2 motion_extent;
    vec2 projection;
    float blend;
    int hdr;
} params;
"""
    main = """
void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    vec4 result = synthesize_pixel(source0, source1, warp0, warp1, scene_map,
                                   pixel, params.output_extent, params.motion_extent,
                                   params.projection, params.blend, params.hdr);
    imageStore(target, pixel, result);
}
"""
    shader = wrapper + sample_header + "\n" + synthesis_header + main
    with tempfile.TemporaryDirectory(prefix="mpvrx-jelly-glsl-") as temp:
        path = Path(temp) / "jelly_synthesis.comp"
        output = Path(temp) / "jelly_synthesis.spv"
        path.write_text(shader, encoding="utf-8")
        result = subprocess.run(
            [validator, "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(output), str(path)],
            capture_output=True,
            text=True,
        )
        if result.returncode:
            raise SystemExit("Patched FongMi synthesis GLSL failed to compile:\n" + result.stdout + result.stderr)
        if not output.is_file() or output.stat().st_size == 0:
            raise SystemExit("glslangValidator produced no jelly shader module")
        print(f"Patched FongMi synthesis GLSL compiled: {output.stat().st_size} bytes SPIR-V")


def check_patch_contract() -> None:
    fixture = f"/* {DOWNSAMPLE_MARKER} */\n" + ORIGINAL_ZERO_FLOW + "\n"
    patched, changed = transform_jelly(fixture, verify_upstream_hash=False)
    assert changed
    assert has_jelly_region_patch(patched)
    assert patched.count(PATCHED_ZERO_FLOW) == 1
    assert patched.count(ORIGINAL_ZERO_FLOW) == 0
    assert patched.count(JELLY_MARKER) == 1
    assert "for (int neighbor = 0; neighbor < 8; neighbor++)" in PATCHED_ZERO_FLOW
    assert "jelly_count0 >= 5" in PATCHED_ZERO_FLOW
    assert "jelly_count1 >= 5" in PATCHED_ZERO_FLOW
    assert "MPVFLOW_JELLY_ZERO_MOTION_REGION_V1" not in PATCHED_ZERO_FLOW
    assert "neighbor_f.xy * jelly_scale" in PATCHED_ZERO_FLOW
    assert "neighbor_r.xy * jelly_scale" in PATCHED_ZERO_FLOW
    majority = PATCHED_ZERO_FLOW.index("for (int neighbor = 0; neighbor < 8; neighbor++)")
    fallback = PATCHED_ZERO_FLOW.index("if (all(equal(vec4(f.xy, r.xy), vec4(0.0)))) {")
    assert majority < fallback, "the test candidate must be evaluated before the legacy zero-flow fallback"
    repeated, changed_again = transform_jelly(patched, verify_upstream_hash=False)
    assert not changed_again and repeated == patched
    with tempfile.TemporaryDirectory(prefix="mpvrx-jelly-disabled-") as temp:
        disabled_path = Path(temp) / "motion.c"
        disabled_source = f"/* {DOWNSAMPLE_MARKER} */\n" + ORIGINAL_ZERO_FLOW + "\n"
        disabled_path.write_text(disabled_source, encoding="utf-8")
        assert not apply_patch(disabled_path, enabled=False)
        assert disabled_path.read_text(encoding="utf-8") == disabled_source
        disabled_path.write_text(patched, encoding="utf-8")
        try:
            apply_patch(disabled_path, enabled=False)
        except ValueError:
            pass
        else:
            raise AssertionError("a regular build silently accepted an opt-in jelly patch")
    try:
        transform_jelly(fixture.replace(ORIGINAL_ZERO_FLOW, "return original;"), verify_upstream_hash=False)
    except ValueError:
        pass
    else:
        raise AssertionError("the jelly patch accepted a drifted zero-motion anchor")
    print("Jelly patch idempotence, fail-closed anchor, and opt-in marker checks passed")


def _reference_preview_pixel(pixel: tuple[float, float, float, float], mask: bool, enabled: bool) -> tuple[float, float, float, float]:
    if not enabled or not mask:
        return pixel
    alpha = 0.72
    tint = (1.0, 0.0, 1.0)
    return tuple((1.0 - alpha) * pixel[i] + alpha * tint[i] for i in range(3)) + (pixel[3],)


def check_preview_contract() -> None:
    fixture = f"/* {DOWNSAMPLE_MARKER} */\n" + ORIGINAL_ZERO_FLOW + "\n"
    applied, applied_changed = transform_jelly(fixture, verify_upstream_hash=False)
    preview, preview_changed = transform_preview_source(fixture, verify_upstream_hash=False)
    assert applied_changed and preview_changed
    assert has_jelly_region_patch(applied)
    assert has_jelly_preview_patch(preview)
    assert preview.count(PREVIEW_MARKER) == 1
    assert preview.count(PREVIEW_RETURN) == 1
    assert preview.count(SYNTHESIS_RETURN) == 0
    assert "return mix(original, jelly_region, jelly_fill);" not in preview
    assert "if (jelly_fill > 0.001)" in preview
    assert preview.index("if (jelly_fill > 0.001)") < preview.index(PREVIEW_MARKER)
    assert preview.replace(PATCHED_ZERO_FLOW_PREVIEW, PATCHED_ZERO_FLOW, 1) == applied
    assert PATCHED_ZERO_FLOW_PREVIEW.replace(PREVIEW_RETURN, SYNTHESIS_RETURN, 1) == PATCHED_ZERO_FLOW

    repeated, changed_again = transform_preview_source(preview, verify_upstream_hash=False)
    assert not changed_again and repeated == preview

    with tempfile.TemporaryDirectory(prefix="mpvrx-jelly-preview-disabled-") as temp:
        disabled_path = Path(temp) / "motion.c"
        disabled_path.write_text(fixture, encoding="utf-8")
        assert not apply_preview_patch(disabled_path, enabled=False)
        assert disabled_path.read_text(encoding="utf-8") == fixture
        disabled_path.write_text(preview, encoding="utf-8")
        try:
            apply_preview_patch(disabled_path, enabled=False)
        except ValueError:
            pass
        else:
            raise AssertionError("regular source preparation silently accepted the Jelly preview patch")

    # Preview-off and mask-off pixels are bit-identical; only mask-positive pixels receive tint.
    pixels = (
        (0.15, 0.55, 0.72, 1.0),
        (0.40, 0.40, 0.40, 1.0),
        (0.08, 0.12, 0.20, 0.8),
    )
    for pixel in pixels:
        assert _reference_preview_pixel(pixel, mask=False, enabled=True) == pixel
        assert _reference_preview_pixel(pixel, mask=True, enabled=False) == pixel
    flags = (False, True, False)
    for pixel, flag in zip(pixels, flags, strict=True):
        result = _reference_preview_pixel(pixel, mask=flag, enabled=True)
        assert (result != pixel) is flag
        assert result[3] == pixel[3]

    # The candidate and preview differ only at the selected pixel's final color return.
    # Motion samples/warps, blend phase and all cadence inputs remain byte-for-byte unchanged.
    assert preview.replace(PATCHED_ZERO_FLOW_PREVIEW, PATCHED_ZERO_FLOW, 1) == applied
    for token in (
        "vec4 neighbor_f = textureLod(warp0, neighbor_uv, 0.0);",
        "vec4 neighbor_r = textureLod(warp1, neighbor_uv, 0.0);",
        "neighbor_f.xy * jelly_scale",
        "neighbor_r.xy * jelly_scale",
        "float jelly_phase = 4.0 * blend * (1.0 - blend);",
    ):
        assert token in preview and token in applied
    assert "motion_fps" not in PREVIEW_RETURN and "frame_duration" not in PREVIEW_RETURN
    print("Jelly preview pixel identity, mask-only tint, unchanged motion inputs/cadence, and synthesis-off checks passed")


def prepared_variant(source: str, *, preview_only: bool = False) -> str:
    if preview_only:
        if has_jelly_preview_patch(source):
            if hashlib.sha256(source.encode()).hexdigest() != MOTION_JELLY_PREVIEW_SHA256:
                raise SystemExit("Jelly preview marker is present but the final pinned-source hash is unexpected")
            return source
        if has_jelly_region_patch(source):
            jelly_source = source
        else:
            jelly_source, _ = transform_downsample(source)
        preview_source, _ = transform_preview_source(jelly_source)
        digest = hashlib.sha256(preview_source.encode()).hexdigest()
        if digest != MOTION_JELLY_PREVIEW_SHA256:
            raise SystemExit(f"Unexpected opt-in Jelly preview source hash: {digest}")
        return preview_source
    if PREVIEW_MARKER in source:
        raise SystemExit("Jelly preview source cannot be used as the synthesis candidate")
    if has_jelly_region_patch(source):
        if hashlib.sha256(source.encode()).hexdigest() != MOTION_JELLY_SHA256:
            raise SystemExit("Jelly marker is present but the final pinned-source hash is unexpected")
        return source
    aa_source, _ = transform_downsample(source)
    jelly_source, _ = transform_jelly(aa_source)
    digest = hashlib.sha256(jelly_source.encode()).hexdigest()
    if digest != MOTION_JELLY_SHA256:
        raise SystemExit(f"Unexpected opt-in jelly source hash: {digest}")
    return jelly_source


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", type=Path, help="pinned MPV source checkout")
    parser.add_argument("--motion-source", type=Path, help="motion.c to transform or verify")
    parser.add_argument("--require-glslang", action="store_true")
    parser.add_argument("--preview-only", action="store_true", help="compile the overlay-only mask preview shader")
    args = parser.parse_args()
    if bool(args.mpv_dir) == bool(args.motion_source):
        parser.error("provide exactly one of --mpv-dir or --motion-source")

    check_patch_contract()
    check_preview_contract()
    if args.mpv_dir:
        mpv_dir = args.mpv_dir.resolve()
        try:
            verify_mpv_source(mpv_dir)
        except (OSError, ValueError) as exc:
            parser.exit(1, f"Pinned MPVRx source verification failed: {exc}\n")
        motion_path = mpv_dir / "video/out/gpu_next/motion.c"
    else:
        motion_path = args.motion_source.resolve()
    source = motion_path.read_text(encoding="utf-8")
    final_source = prepared_variant(source, preview_only=args.preview_only)
    compile_synthesis_shader(final_source, required=args.require_glslang)
    mode_hash = MOTION_JELLY_PREVIEW_SHA256 if args.preview_only else MOTION_JELLY_SHA256
    suffix = "; synthesis disabled" if args.preview_only else ""
    print(f"Opt-in motion-aligned majority jelly {'mask preview' if args.preview_only else 'candidate'} verified ({mode_hash}){suffix}")


if __name__ == "__main__":
    main()
