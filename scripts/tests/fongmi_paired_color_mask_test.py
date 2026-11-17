#!/usr/bin/env python3
"""Regression checks for FongMi's paired-color mask and selective contour resolver."""
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


def path_disagreement(
    displacement0: tuple[float, float],
    displacement1: tuple[float, float],
    projection: tuple[float, float],
) -> float:
    error0 = (
        displacement1[0] + displacement0[0] * projection[0],
        displacement1[1] + displacement0[1] * projection[0],
    )
    error1 = (
        displacement0[0] + displacement1[0] * projection[1],
        displacement0[1] + displacement1[1] * projection[1],
    )
    return max(math.hypot(*error0), math.hypot(*error1))


def selective_visibility(
    center_color_error: float,
    trajectory_disagreement: float,
    errors0: list[float],
    errors1: list[float],
    visibility: tuple[float, float],
    *,
    fixed_weights: bool = False,
) -> tuple[float, float]:
    """CPU mirror of the strict, downward-only GLSL resolver gate."""
    resolved = [visibility[0], visibility[1]]
    center_weight = smoothstep(
        PATCHER.RESOLVER_CENTER_ERROR_START,
        PATCHER.RESOLVER_CENTER_ERROR_FULL,
        center_color_error,
    )
    if fixed_weights or trajectory_disagreement <= PATCHER.RESOLVER_PATH_ERROR_START:
        return tuple(resolved)
    if center_weight < PATCHER.RESOLVER_CENTER_WEIGHT_MIN:
        return tuple(resolved)

    support0 = patch_support(errors0)
    support1 = patch_support(errors1)
    best = max(support0, support1)
    worst = min(support0, support1)
    if best < PATCHER.RESOLVER_SUPPORT_GOOD or worst > PATCHER.RESOLVER_SUPPORT_BAD:
        return tuple(resolved)

    path_weight = smoothstep(
        PATCHER.RESOLVER_PATH_ERROR_START,
        PATCHER.RESOLVER_PATH_ERROR_FULL,
        trajectory_disagreement,
    )
    reject_weight = center_weight * path_weight
    if support0 > support1:
        resolved[1] *= 1.0 - reject_weight
    else:
        resolved[0] *= 1.0 - reject_weight
    return tuple(resolved)


def blend_pixel(
    original: float,
    warped0: float,
    warped1: float,
    visibility: tuple[float, float],
) -> float:
    return (
        original * (1.0 - visibility[0] - visibility[1])
        + warped0 * visibility[0]
        + warped1 * visibility[1]
    )


def compile_resolver(source: str) -> None:
    validator = shutil.which("glslangValidator")
    if validator is None:
        print("Skipping selective resolver GLSL compile: glslangValidator is unavailable")
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
    vec2 motion_extent = vec2(16.0);
    ivec2 pixel = ivec2(8, 8);
    vec2 displacement0 = vec2(-4.0, 0.0);
    vec2 displacement1 = vec2(3.0, 0.0);
    vec2 projection = vec2(1.0, 1.0);
    vec2 p0 = vec2(pixel) + displacement0;
    vec2 p1 = vec2(pixel) + displacement1;
    vec4 original = vec4(0.4, 0.4, 0.4, 1.0);
    vec4 warped0 = vec4(0.9, 0.9, 0.9, 1.0);
    vec4 warped1 = vec4(0.1, 0.1, 0.1, 1.0);
    vec2 visibility = vec2(0.25, 0.25);
    bool fixed_weights = false;
    int hdr = 0;
{PATCHER.ARTIFACT_STRENGTH_BLOCK.rstrip()}
}}
void main() {{ imageStore(output_image, ivec2(0), run_resolver()); }}
"""
    with tempfile.TemporaryDirectory(prefix="fongmi-selective-contour-") as tmp:
        shader_path = Path(tmp) / "selective_contour.comp"
        spirv_path = Path(tmp) / "selective_contour.spv"
        shader_path.write_text(shader, encoding="utf-8")
        result = subprocess.run(
            [validator, "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(spirv_path), str(shader_path)],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(f"GLSL rejected the selective contour resolver:\n{result.stdout}\n{result.stderr}")
        assert spirv_path.is_file() and spirv_path.stat().st_size > 0
    print("Mpv-infinity selective contour resolver GLSL compiled to Vulkan SPIR-V")


def verify_reprepare_cycle(source: str) -> None:
    """Model the build script's second prepare call without changing synthesis."""
    base_source = PATCHER.unpatch_source(source)
    antialias_enabled = DOWNSAMPLE.has_antialias_filter(base_source)
    with tempfile.TemporaryDirectory(prefix="fongmi-reprepare-cycle-") as tmp:
        motion_path = Path(tmp) / "motion.c"
        motion_path.write_text(source, encoding="utf-8")
        assert PATCHER.strip_patch(motion_path), "the prior prepare pass must be stripped"
        DOWNSAMPLE.apply_patch(motion_path, enabled=antialias_enabled)
        assert PATCHER.apply_patch(motion_path), "the repeated prepare pass must reapply the resolver"
        assert motion_path.read_text(encoding="utf-8") == source, "reprepare must preserve the exact resolver source"


def source_fixture() -> str:
    return (
        "float conflict_weight = smoothstep(0.03, 0.15, color_error);\n"
        "                    vec2 paired_error = vec2(max(error0.r, max(error0.g, error0.b)),\n"
        "                                             max(error1.r, max(error1.g, error1.b)));\n"
        + PATCHER.ORIGINAL_BLOCK
        + "                    vec2 evidence = support * vec2(relative_fit(stationary_error, paired_error.x),\n"
        + PATCHER.VISIBILITY_ANCHOR
        + PATCHER.FINAL_BLEND_ANCHOR
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, help="raw or prepared pinned FongMi motion.c")
    args = parser.parse_args()

    original = args.source.read_text(encoding="utf-8") if args.source else source_fixture()
    expected_base = PATCHER.unpatch_source(original) if PATCHER.MARKER in original else original
    patched, changed = PATCHER.transform_source(original)
    assert changed or PATCHER.ARTIFACT_STRENGTH_MARKER in original
    assert PATCHER.has_paired_color_mask(patched)
    assert patched.count(PATCHER.ARTIFACT_STRENGTH_BLOCK) == 1
    block = PATCHER.ARTIFACT_STRENGTH_BLOCK
    assert f"path_disagreement > {PATCHER.RESOLVER_PATH_ERROR_START:.1f}" in block
    assert f"center_conflict_weight >= {PATCHER.RESOLVER_CENTER_WEIGHT_MIN:.2f}" in block
    assert f"best_support >= {PATCHER.RESOLVER_SUPPORT_GOOD:.2f}" in block
    assert f"worst_support <= {PATCHER.RESOLVER_SUPPORT_BAD:.2f}" in block
    assert f"smoothstep({PATCHER.RESOLVER_CENTER_ERROR_START:.2f}, {PATCHER.RESOLVER_CENTER_ERROR_FULL:.2f}, center_color_error)" in block
    assert f"smoothstep({PATCHER.RESOLVER_PATH_ERROR_START:.1f}, {PATCHER.RESOLVER_PATH_ERROR_FULL:.1f}, path_disagreement)" in block
    assert "resolved_visibility.y *= 1.0 - reject_weight;" in block
    assert "resolved_visibility.x *= 1.0 - reject_weight;" in block
    assert "resolved_visibility" in block and "local_owner" not in block
    assert "directional_evidence" not in block and "unsupported = 1.0 - max" not in block
    assert PATCHER.FINAL_BLEND_ANCHOR not in patched
    patched_again, changed_again = PATCHER.transform_source(patched)
    assert not changed_again and patched_again == patched, "mask patch must be idempotent"

    legacy = expected_base.replace(PATCHER.ORIGINAL_BLOCK, PATCHER.PATCHED_BLOCK, 1)
    assert PATCHER.has_legacy_paired_color_mask(legacy), "the paired-color-only mask must remain recognizable"
    upgraded_legacy, changed_legacy = PATCHER.transform_source(legacy)
    assert changed_legacy and upgraded_legacy == patched, "the legacy mask must upgrade to the selective resolver"
    assert PATCHER.unpatch_source(legacy) == expected_base

    prior_blocks = (
        PATCHER.ARTIFACT_STRENGTH_V4_BLOCK,
        PATCHER.ARTIFACT_STRENGTH_V3_BLOCK,
        PATCHER.ARTIFACT_STRENGTH_V2_BLOCK,
        PATCHER.ARTIFACT_STRENGTH_V1_BLOCK,
    )
    for prior_block in prior_blocks:
        prior = expected_base.replace(PATCHER.ORIGINAL_BLOCK, PATCHER.PATCHED_BLOCK, 1)
        prior = prior.replace(PATCHER.VISIBILITY_ANCHOR, PATCHER.VISIBILITY_ANCHOR + prior_block, 1)
        assert PATCHER.has_paired_color_mask(prior), "each supported prior gate must remain recognizable"
        upgraded_prior, changed_prior = PATCHER.transform_source(prior)
        assert changed_prior and upgraded_prior == patched, "each prior gate must upgrade to the selective resolver"
        assert PATCHER.unpatch_source(prior) == expected_base

    unpatched = PATCHER.unpatch_source(patched)
    repatched, repatched_changed = PATCHER.transform_source(unpatched)
    assert repatched_changed and repatched == patched, "the patch must change only supported source blocks"
    assert unpatched == expected_base, "unpatching must restore the exact pre-mask source"
    if args.source:
        verify_reprepare_cycle(patched)

    # The paired evidence taper keeps close matches while reducing ambiguous witnesses sooner.
    assert math.isclose(patch_support([0.015, 0.015, 0.015]), 1.0, abs_tol=1e-12)
    assert patch_support([0.04, 0.04, 0.04]) < 1.0
    assert patch_support([0.08, 0.08, 0.08]) == 0.0

    # Exact reported counterexample: zero center conflict and patch errors 0.03 vs 0.035
    # must return the legacy blend despite a large trajectory disagreement.
    old_visibility = (0.25, 0.30)
    counterexample_visibility = selective_visibility(
        0.0, 3.5, [0.03] * 3, [0.035] * 3, old_visibility
    )
    assert counterexample_visibility == old_visibility
    original_blend = blend_pixel(0.4, 0.95, 0.2, old_visibility)
    assert blend_pixel(0.4, 0.95, 0.2, counterexample_visibility) == original_blend

    # A coherent quarter-phase pan has near-zero trajectory residual even when patch support differs.
    pan_disagreement = path_disagreement((-0.75, 0.0), (2.25, 0.0), (3.0, 1.0 / 3.0))
    assert pan_disagreement < 1e-12
    pan_visibility = selective_visibility(0.20, pan_disagreement, [0.01] * 3, [0.12] * 3, old_visibility)
    assert pan_visibility == old_visibility

    # High conflict and separated paths are still unchanged when local texture supports both candidates.
    balanced_visibility = selective_visibility(0.20, 3.5, [0.01] * 3, [0.015] * 3, old_visibility)
    assert balanced_visibility == old_visibility

    # A merely partial support difference is not enough, even with high center conflict and path error.
    partial_visibility = selective_visibility(0.20, 3.5, [0.01] * 3, [0.05] * 3, old_visibility)
    assert partial_visibility == old_visibility

    # The resolver must leave clean regions unchanged, even if neighboring vectors disagree.
    clean_visibility = selective_visibility(0.0, 3.5, [0.01] * 3, [0.12] * 3, old_visibility)
    assert clean_visibility == old_visibility

    # Positive synthetic case: one contour has consistent local paired-color support; reject only the other lane.
    positive_visibility = selective_visibility(0.20, 3.5, [0.01] * 3, [0.12] * 3, (0.20, 0.30))
    assert positive_visibility == (0.20, 0.0)
    assert positive_visibility[0] == 0.20, "the locally reliable lane must be unchanged"
    before = blend_pixel(0.4, 0.9, 0.1, (0.20, 0.30))
    after = blend_pixel(0.4, 0.9, 0.1, positive_visibility)
    assert math.isclose(before, 0.41, abs_tol=1e-12)
    assert math.isclose(after, 0.50, abs_tol=1e-12)
    assert after != before, "the locally unsupported warped contour must be rejected"
    positive_reverse = selective_visibility(0.20, 3.5, [0.12] * 3, [0.01] * 3, (0.20, 0.30))
    assert positive_reverse == (0.0, 0.30), "the opposite unsupported lane must be rejected symmetrically"

    # Sweep the gates: no attenuation unless all three independent conditions pass.
    support_pairs = ((1.0, 1.0), (1.0, 0.5), (1.0, 0.0))
    support_errors = {1.0: [0.01] * 3, 0.5: [0.05] * 3, 0.0: [0.12] * 3}
    for center_error in (0.0, 0.09, 0.10, 0.12, 0.125, 0.15, 0.20):
        for path_error in (0.0, 0.5, 1.5, 2.0, 3.0, 3.5):
            for support0, support1 in support_pairs:
                errors0 = support_errors[support0]
                errors1 = support_errors[support1]
                result = selective_visibility(center_error, path_error, errors0, errors1, old_visibility)
                support0_actual, support1_actual = patch_support(errors0), patch_support(errors1)
                should_gate = (
                    smoothstep(PATCHER.RESOLVER_CENTER_ERROR_START, PATCHER.RESOLVER_CENTER_ERROR_FULL, center_error)
                    >= PATCHER.RESOLVER_CENTER_WEIGHT_MIN
                    and path_error > PATCHER.RESOLVER_PATH_ERROR_START
                    and max(support0_actual, support1_actual) >= PATCHER.RESOLVER_SUPPORT_GOOD
                    and min(support0_actual, support1_actual) <= PATCHER.RESOLVER_SUPPORT_BAD
                )
                if not should_gate:
                    assert result == old_visibility
                elif support0_actual > support1_actual:
                    assert result[0] == old_visibility[0]
                    assert result[1] <= old_visibility[1]
                else:
                    assert result[1] == old_visibility[1]
                    assert result[0] <= old_visibility[0]

    compile_resolver(patched)
    print(
        "Mpv-infinity selective contour checks passed: zero-conflict 0.03/0.035 stays on the old blend; "
        "clean pans, balanced texture, partial support and clean regions are unchanged; "
        "only strong conflict plus separated paths and a clear local support split suppress one lane"
    )


if __name__ == "__main__":
    main()
