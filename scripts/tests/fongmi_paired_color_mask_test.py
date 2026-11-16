#!/usr/bin/env python3
"""Regression checks for FongMi's local contour ownership resolver."""
from __future__ import annotations

import argparse
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import patch_fongmi_motion_downsample as DOWNSAMPLE  # noqa: E402
import patch_fongmi_paired_color_mask as PATCHER  # noqa: E402


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    t = min(1.0, max(0.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def patch_support(errors: list[float]) -> float:
    return 1.0 - smoothstep(0.02, 0.08, sum(errors) / len(errors))


def path_resolve(displacement0: tuple[float, float], displacement1: tuple[float, float],
                 projection: tuple[float, float]) -> float:
    error0 = (displacement1[0] + displacement0[0] * projection[0],
              displacement1[1] + displacement0[1] * projection[0])
    error1 = (displacement0[0] + displacement1[0] * projection[1],
              displacement0[1] + displacement1[1] * projection[1])
    disagreement = max(math.hypot(*error0), math.hypot(*error1))
    return smoothstep(0.5, 1.5, disagreement)


def local_resolve_factor(path_weight: float, support0: float, support1: float,
                         ownership0: float, ownership1: float) -> float:
    directional_evidence = smoothstep(0.01, 0.10, abs(ownership0 - ownership1))
    unsupported = 1.0 - max(support0, support1)
    return path_weight * max(directional_evidence, unsupported)


def select_owner(errors0: list[float], errors1: list[float], confidence0: float,
                 confidence1: float, blend: float) -> int | None:
    score0 = patch_support(errors0) * confidence0
    score1 = patch_support(errors1) * confidence1
    if not math.isfinite(score0) or not math.isfinite(score1):
        return None
    if max(score0, score1) <= 0.001:
        return None
    if score0 > score1:
        return 0
    if score1 > score0:
        return 1
    return 0 if blend < 0.5 else 1


def compile_ownership_resolver(source: str) -> None:
    validator = shutil.which("glslangValidator")
    if validator is None:
        print("Skipping local ownership GLSL compile: glslangValidator is unavailable")
        return
    if not PATCHER.has_paired_color_mask(source):
        raise AssertionError("GLSL compile requires the validated patched FongMi source")
    shader = f"""#version 450
layout(local_size_x = 1, local_size_y = 1) in;
layout(set = 0, binding = 0) uniform sampler2D source0;
layout(set = 0, binding = 1) uniform sampler2D source1;
layout(set = 0, binding = 2, rgba32f) uniform writeonly image2D output_image;
vec3 synthesis_pq(vec3 color) {{ return color; }}
vec3 sample_witness(sampler2D image, vec2 p, vec2 extent, vec2 texel_size) {{
    return textureLod(image, (clamp(p, vec2(0.0), extent - 1.0) + 0.5) * texel_size, 0.0).rgb;
}}
vec4 run_resolver() {{
    vec2 output_extent = vec2(16.0);
    vec2 pixel = vec2(8.0);
    vec2 displacement0 = vec2(-3.0, 0.0);
    vec2 displacement1 = vec2(1.0, 0.0);
    vec2 projection = vec2(3.0, 0.3333333);
    vec2 p0 = pixel + displacement0;
    vec2 p1 = pixel + displacement1;
    float blend = 0.5;
    int hdr = 0;
    vec4 original0 = textureLod(source0, vec2(0.5), 0.0);
    vec4 original1 = textureLod(source1, vec2(0.5), 0.0);
    vec4 original = mix(original0, original1, blend);
    vec4 warped0 = textureLod(source0, vec2(0.5), 0.0);
    vec4 warped1 = textureLod(source1, vec2(0.5), 0.0);
    vec4 f = vec4(0.0, 0.0, 0.8, 0.9);
    vec4 r = vec4(0.0, 0.0, 0.8, 0.9);
    vec2 visibility = vec2(0.25, 0.25);
{PATCHER.ARTIFACT_STRENGTH_BLOCK.rstrip()}
}}
void main() {{ imageStore(output_image, ivec2(0), run_resolver()); }}
"""
    with tempfile.TemporaryDirectory(prefix="mpv-infinity-contour-ownership-") as tmp:
        shader_path = Path(tmp) / "local_contour_ownership.comp"
        spirv_path = Path(tmp) / "local_contour_ownership.spv"
        shader_path.write_text(shader, encoding="utf-8")
        result = subprocess.run(
            [validator, "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(spirv_path), str(shader_path)],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(f"GLSL rejected the local ownership resolver:\n{result.stdout}\n{result.stderr}")
        assert spirv_path.is_file() and spirv_path.stat().st_size > 0
    print("FongMi local contour ownership GLSL compiled to Vulkan SPIR-V")


def verify_reprepare_cycle(source: str) -> None:
    """Model the build script's second prepare call without changing synthesis."""
    base_source = PATCHER.unpatch_source(source)
    antialias_enabled = DOWNSAMPLE.has_antialias_filter(base_source)
    with tempfile.TemporaryDirectory(prefix="mpv-infinity-reprepare-cycle-") as tmp:
        motion_path = Path(tmp) / "motion.c"
        motion_path.write_text(source, encoding="utf-8")
        assert PATCHER.strip_patch(motion_path), "the prior prepare pass must be stripped"
        DOWNSAMPLE.apply_patch(motion_path, enabled=antialias_enabled)
        assert PATCHER.apply_patch(motion_path), "the repeated prepare pass must reapply the resolver"
        assert motion_path.read_text(encoding="utf-8") == source, "reprepare must preserve the exact resolver source"


def make_base_source() -> str:
    return (
        PATCHER.CONFLICT_WEIGHT + "\n"
        + PATCHER.PAIRED_ERROR_ANCHOR + "\n"
        + PATCHER.ORIGINAL_BLOCK
        + "                    vec2 evidence = support * vec2(relative_fit(stationary_error, paired_error.x),\n"
        + PATCHER.VISIBILITY_ANCHOR
        + PATCHER.FINAL_BLEND_ANCHOR
    )


def count_visible_copies(image: list[float], threshold: float = 0.1) -> int:
    return sum(value > threshold for value in image)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, help="raw or prepared pinned FongMi motion.c")
    args = parser.parse_args()
    original = args.source.read_text(encoding="utf-8") if args.source else make_base_source()
    expected_base = PATCHER.unpatch_source(original) if PATCHER.MARKER in original else original

    patched, changed = PATCHER.transform_source(original)
    assert changed or PATCHER.ARTIFACT_STRENGTH_MARKER in patched
    assert PATCHER.has_paired_color_mask(patched)
    assert patched.count(PATCHER.ARTIFACT_STRENGTH_BLOCK) == 1
    assert PATCHER.FINAL_BLEND_ANCHOR not in patched
    assert "path_error0 = displacement1 + displacement0 * projection.x" in patched
    assert "sample_witness(source0, p0 + offset" in patched
    assert "sample_witness(source1, paired1_position + offset" in patched
    assert "return mix(blended_output, local_owner, local_resolve);" in patched
    assert "visibility *= mix(vec2(1.0), local_warp_keep, conflict_weight);" not in patched
    patched_again, changed_again = PATCHER.transform_source(patched)
    assert not changed_again and patched_again == patched, "the resolver patch must be idempotent"

    legacy = expected_base.replace(PATCHER.ORIGINAL_BLOCK, PATCHER.PATCHED_BLOCK, 1)
    assert PATCHER.has_legacy_paired_color_mask(legacy), "the paired-color-only mask must remain recognizable"
    upgraded, upgraded_changed = PATCHER.transform_source(legacy)
    assert upgraded_changed and upgraded == patched, "the paired-color-only mask must upgrade to local ownership"
    assert PATCHER.unpatch_source(legacy) == expected_base

    prior_blocks = (
        PATCHER.ARTIFACT_STRENGTH_V1_BLOCK,
        PATCHER.ARTIFACT_STRENGTH_V2_BLOCK,
        PATCHER.ARTIFACT_STRENGTH_V3_BLOCK,
        PATCHER.ARTIFACT_STRENGTH_V4_BLOCK,
    )
    for prior_block in prior_blocks:
        prior = legacy.replace(PATCHER.VISIBILITY_ANCHOR,
                               PATCHER.VISIBILITY_ANCHOR + prior_block, 1)
        assert PATCHER.has_paired_color_mask(prior), "each shipped prior patch must remain recognizable"
        upgraded_prior, changed_prior = PATCHER.transform_source(prior)
        assert changed_prior and upgraded_prior == patched, "prior patches must upgrade to local ownership"
        assert PATCHER.unpatch_source(prior) == expected_base

    unpatched = PATCHER.unpatch_source(patched)
    repatched, repatched_changed = PATCHER.transform_source(unpatched)
    assert repatched_changed and repatched == patched
    assert unpatched == expected_base, "unpatching must restore the exact pre-mask source"
    if args.source:
        verify_reprepare_cycle(patched)

    # A clean global pan has phase-consistent inverse paths and is left untouched.
    assert path_resolve((-4.0, 0.0), (4.0, 0.0), (1.0, 1.0)) == 0.0
    assert path_resolve((-1.0, 0.0), (3.0, 0.0), (3.0, 1.0 / 3.0)) == 0.0
    for alpha in (0.1, 0.25, 0.5, 0.75, 0.9):
        phase_projection = ((1.0 - alpha) / alpha, alpha / (1.0 - alpha))
        camera_motion = (3.25, -2.5)
        displacement0 = (-alpha * camera_motion[0], -alpha * camera_motion[1])
        displacement1 = ((1.0 - alpha) * camera_motion[0],
                         (1.0 - alpha) * camera_motion[1])
        assert path_resolve(displacement0, displacement1, phase_projection) == 0.0

    # Same gold center pixels fool the prior color-conflict gate; neighboring
    # source-patch context identifies which inverse lane tracks the contour.
    same_gold_center_error = 0.0
    prior_conflict_weight = smoothstep(0.03, 0.15, same_gold_center_error)
    assert prior_conflict_weight == 0.0
    assert path_resolve((-3.0, 0.0), (1.0, 0.0), (3.0, 1.0 / 3.0)) == 1.0
    wrong_patch = [0.0, 0.40, 0.40]
    matching_patch = [0.0, 0.005, 0.005]
    assert patch_support(wrong_patch) == 0.0
    assert patch_support(matching_patch) == 1.0
    support0 = patch_support(wrong_patch)
    support1 = patch_support(matching_patch)
    owner0, owner1 = support0 * 0.9, support1 * 0.95
    assert local_resolve_factor(1.0, support0, support1, owner0, owner1) == 1.0
    assert select_owner(wrong_patch, matching_patch, 0.9, 0.95, 0.5) == 1

    # Large path residual alone is not sufficient: equally supported local
    # texture preserves the existing output instead of forcing an owner.
    assert local_resolve_factor(1.0, 1.0, 1.0, 0.9, 0.9) == 0.0

    # The production blend can retain a source crossfade plus two warp samples.
    # The local owner path collapses that ambiguous contour to one supported lane.
    original0 = [0.0] * 12
    original1 = [0.0] * 12
    warped0 = [0.0] * 12
    warped1 = [0.0] * 12
    original0[2] = 1.0
    original1[4] = 1.0
    warped0[4] = 1.0
    warped1[9] = 1.0
    vis0 = vis1 = 0.25
    original = [(a + b) * 0.5 for a, b in zip(original0, original1)]
    blended = [base * (1.0 - vis0 - vis1) + a * vis0 + b * vis1
               for base, a, b in zip(original, warped0, warped1)]
    assert count_visible_copies(blended) == 3
    selected_owner = warped1
    resolved = [(base * (1.0 - 1.0) + owner) for base, owner in zip(blended, selected_owner)]
    assert count_visible_copies(resolved) == 1

    # No local patch support falls back to one nearest source endpoint, not a
    # three-layer blend; the selection remains local to the ambiguous pixel.
    no_support = [1.0] * 3
    assert select_owner(no_support, no_support, 0.9, 0.9, 0.25) is None
    assert select_owner(no_support, no_support, 0.9, 0.9, 0.75) is None
    assert local_resolve_factor(1.0, 0.0, 0.0, 0.0, 0.0) == 1.0
    assert count_visible_copies(original0) == 1
    assert count_visible_copies(original1) == 1

    compile_ownership_resolver(patched)
    print(
        "FongMi local ownership passed: three path-aligned patch samples resolve same-color contour ambiguity, "
        "subpixel camera motion and equally supported local texture are unchanged, nearest-frame fallback is local, "
        "and V1-V4/reprepare upgrades are reversible and idempotent"
    )


if __name__ == "__main__":
    main()
