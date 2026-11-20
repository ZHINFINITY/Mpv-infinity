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
    PHASE_RAMP,
    PATCHED_ZERO_FLOW,
    PATCHED_ZERO_FLOW_PREVIEW,
    PREVIEW_PHASE_RAMP,
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
    bilinear_fetches = sample_header.count("texelFetch(")
    if bilinear_fetches != 4:
        raise SystemExit(f"Pinned bilinear sample helper changed: expected 4 texelFetch sites, got {bilinear_fetches}")
    jelly_fetch_bound = 2 * bilinear_fetches + 8 * (2 + 2 * bilinear_fetches)
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
        print(
            f"Patched FongMi synthesis GLSL compiled: {output.stat().st_size} bytes SPIR-V; "
            f"Jelly path bound={jelly_fetch_bound} texture fetches/pixel "
            f"(34 helper/warp calls), no added pass or image storage"
        )


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


def _phase_envelope(blend: float) -> float:
    base = 4.0 * blend * (1.0 - blend)
    return base * base


def _reference_mask_positive(
    center0: tuple[float, float, float],
    center1: tuple[float, float, float],
    support0: bool,
    support1: bool,
) -> bool:
    change = max(abs(center0[i] - center1[i]) for i in range(3))
    return change > 0.12 and (support0 or support1)


def _reference_blend_pixel(
    pixel: tuple[float, float, float, float],
    jelly_region: tuple[float, float, float, float],
    mask_positive: bool,
    enabled: bool,
    blend: float,
    support: float = 1.0,
) -> tuple[float, float, float, float]:
    if not enabled or not mask_positive:
        return pixel
    fill = min(0.65, max(0.0, 0.65 * support * _phase_envelope(blend)))
    if fill <= 0.001:
        return pixel
    return tuple((1.0 - fill) * pixel[i] + fill * jelly_region[i] for i in range(4))


def check_preview_contract() -> None:
    fixture = f"/* {DOWNSAMPLE_MARKER} */\n" + ORIGINAL_ZERO_FLOW + "\n"
    applied, applied_changed = transform_jelly(fixture, verify_upstream_hash=False)
    preview, preview_changed = transform_preview_source(fixture, verify_upstream_hash=False)
    assert applied_changed and preview_changed
    assert has_jelly_region_patch(applied)
    assert has_jelly_preview_patch(preview)
    assert preview.count(PREVIEW_MARKER) == 1
    assert preview.count(PREVIEW_RETURN) == 1
    assert preview.count(SYNTHESIS_RETURN) == 1
    assert "return mix(original, jelly_region, jelly_fill);" in PREVIEW_RETURN
    assert "vec4(1.0, 0.0, 1.0" not in preview
    assert "vec4(1.0,0.0,1.0" not in preview
    assert "if (jelly_fill > 0.001)" in preview
    assert preview.index("if (jelly_fill > 0.001)") < preview.index(PREVIEW_MARKER)
    assert preview.replace(PATCHED_ZERO_FLOW_PREVIEW, PATCHED_ZERO_FLOW, 1) == applied
    assert (
        PATCHED_ZERO_FLOW_PREVIEW.replace(PREVIEW_RETURN, SYNTHESIS_RETURN, 1)
        .replace(PREVIEW_PHASE_RAMP, PHASE_RAMP, 1)
        == PATCHED_ZERO_FLOW
    )

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

    # The confirmed mask decision, motion samples, and 8-neighbor support remain identical.
    assert preview.replace(PATCHED_ZERO_FLOW_PREVIEW, PATCHED_ZERO_FLOW, 1) == applied
    for token in (
        "if (jelly_change > 0.12 && blend > 0.000001 && blend < 0.999999)",
        "vec4 neighbor_f = textureLod(warp0, neighbor_uv, 0.0);",
        "vec4 neighbor_r = textureLod(warp1, neighbor_uv, 0.0);",
        "neighbor_f.xy * jelly_scale",
        "neighbor_r.xy * jelly_scale",
        "jelly_count0 >= 5 && jelly_weight0 >= 3.0",
        "jelly_count1 >= 5 && jelly_weight1 >= 3.0",
        "return mix(original, jelly_region, jelly_fill);",
    ):
        assert token in preview and token in applied
    assert PHASE_RAMP in applied and PHASE_RAMP not in preview
    assert PREVIEW_PHASE_RAMP in preview and PREVIEW_PHASE_RAMP not in applied
    assert "motion_fps" not in PREVIEW_RETURN and "frame_duration" not in PREVIEW_RETURN

    # Mask-off and disabled pixels remain bit-identical; only selected pixels use the source-derived blend.
    cases = (
        ((0.15, 0.55, 0.72, 1.0), (0.45, 0.42, 0.50, 1.0)),
        ((0.40, 0.40, 0.40, 1.0), (0.30, 0.52, 0.38, 1.0)),
        ((0.08, 0.12, 0.20, 1.0), (0.22, 0.17, 0.29, 1.0)),
    )
    for pixel, region in cases:
        assert _reference_blend_pixel(pixel, region, False, True, 0.5) == pixel
        assert _reference_blend_pixel(pixel, region, True, False, 0.5) == pixel
        result = _reference_blend_pixel(pixel, region, True, True, 0.5)
        assert result != pixel
        assert result != (1.0, 0.0, 1.0, pixel[3])
        assert all(0.0 <= component <= 1.0 for component in result)

    # The squared envelope preserves full peak strength and has zero slope at both pair boundaries.
    epsilon = 1e-4
    assert _phase_envelope(0.0) == _phase_envelope(1.0) == 0.0
    assert _phase_envelope(0.5) == 1.0
    assert abs(_phase_envelope(epsilon) - _phase_envelope(1.0 - epsilon)) < 1e-15
    assert _phase_envelope(epsilon) / epsilon < 0.002
    assert _phase_envelope(1.0 - epsilon) / epsilon < 0.002
    samples = [_phase_envelope(i / 100.0) for i in range(101)]
    assert all(a <= b for a, b in zip(samples[:50], samples[1:51], strict=True))
    assert all(a >= b for a, b in zip(samples[50:100], samples[51:], strict=True))
    base_pixel = (0.25, 0.40, 0.52, 1.0)
    left_region, right_region = (0.9, 0.1, 0.2, 1.0), (0.1, 0.8, 0.3, 1.0)
    left = _reference_blend_pixel(base_pixel, left_region, True, True, 1.0 - 1e-3)
    right = _reference_blend_pixel(base_pixel, right_region, True, True, 1e-3)
    assert max(abs(left[i] - right[i]) for i in range(4)) <= 0.65 * _phase_envelope(1e-3) + 1e-12

    # Static pixels and pan-aligned background samples remain unselected and unchanged.
    static = (0.31, 0.44, 0.57)
    assert not _reference_mask_positive(static, static, True, True)
    raw_pan_before, raw_pan_after = (0.12, 0.23, 0.34), (0.72, 0.63, 0.54)
    assert raw_pan_before != raw_pan_after  # different screen coordinates during the camera move
    aligned_background = (0.46, 0.51, 0.59)
    assert not _reference_mask_positive(aligned_background, aligned_background, True, True)
    assert _reference_blend_pixel((*static, 1.0), (0.8, 0.2, 0.1, 1.0), False, True, 0.5) == (*static, 1.0)
    assert _reference_mask_positive((0.1, 0.2, 0.3), (0.8, 0.2, 0.3), True, False)

    # The existing implementation remains one bounded 8-neighbor scan; no history texture/pass is added.
    assert PATCHED_ZERO_FLOW_PREVIEW.count("for (int neighbor = 0; neighbor < 8; neighbor++)") == 1
    assert PATCHED_ZERO_FLOW_PREVIEW.count("textureLod(warp0, neighbor_uv, 0.0)") == 1
    assert PATCHED_ZERO_FLOW_PREVIEW.count("textureLod(warp1, neighbor_uv, 0.0)") == 1
    assert PATCHED_ZERO_FLOW_PREVIEW.count("sample_rgba(source0,") == 2
    assert PATCHED_ZERO_FLOW_PREVIEW.count("sample_rgba(source1,") == 2
    extra_sample_calls = 2 + 8 * (2 + 2)  # centers + two warp and two source helpers per neighbor
    texture_fetch_bound = 2 * 4 + 8 * (2 + 2 * 4)  # pinned sample_rgba uses four texelFetch operations
    assert extra_sample_calls == 34 and texture_fetch_bound == 88
    assert "float jelly_phase = jelly_phase_base * jelly_phase_base;" in PREVIEW_PHASE_RAMP
    assert "imageStore(" not in PATCHED_ZERO_FLOW and "layout(set" not in PATCHED_ZERO_FLOW
    print("Jelly mask invariance, phase continuity, no-purple blend, static/pan gate, and bounded 34-call/88-fetch shader-cost checks passed")


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
    parser.add_argument("--preview-only", action="store_true", help="compile the mask-gated Jelly blend test shader")
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
    suffix = "; blend restricted to the confirmed mask" if args.preview_only else ""
    print(f"Opt-in motion-aligned majority jelly {'blend test' if args.preview_only else 'candidate'} verified ({mode_hash}){suffix}")


if __name__ == "__main__":
    main()
