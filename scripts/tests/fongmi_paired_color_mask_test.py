#!/usr/bin/env python3
"""Regression checks for the active FongMi paired-color and directional warp gate."""
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


def support(value: float, low: float, high: float) -> float:
    return 1.0 - smoothstep(low, high, value)


def directional_visibility_scale(conflict_weight: float, paired_evidence: float, geometry: float) -> float:
    """Mirror one source lane's conflict-only paired-color and cycle gate."""
    local_support = paired_evidence * geometry
    if not math.isfinite(local_support):
        local_support = 0.0
    local_support = min(1.0, max(0.0, local_support))
    local_reliability = smoothstep(0.10, 0.60, local_support)
    return 1.0 + (local_reliability - 1.0) * conflict_weight


def compile_mask_statement(source: str) -> None:
    validator = shutil.which("glslangValidator")
    if validator is None:
        print("Skipping paired-color mask GLSL compile: glslangValidator is unavailable")
        return
    if not PATCHER.has_paired_color_mask(source):
        raise AssertionError("GLSL compile requires the validated patched FongMi source")
    shader = f"""#version 450
layout(local_size_x = 1, local_size_y = 1) in;
void main() {{
    vec2 paired_error = vec2(0.04, 0.06);
    vec2 support = vec2(1.0) - smoothstep(vec2(0.02), vec2(0.08), paired_error);
    vec2 evidence = vec2(0.25, 0.85);
    vec4 f = vec4(0.0, 0.0, 0.0, 0.90);
    vec4 r = vec4(0.0, 0.0, 0.0, 0.80);
    float conflict_weight = 0.6;
    vec2 visibility = vec2(0.4, 0.5);
{PATCHER.ARTIFACT_STRENGTH_BLOCK.rstrip()}
    if (any(isnan(support)) || any(isnan(visibility))) {{
        return;
    }}
}}
"""
    with tempfile.TemporaryDirectory(prefix="fongmi-paired-color-mask-") as tmp:
        shader_path = Path(tmp) / "paired_color_mask.comp"
        spirv_path = Path(tmp) / "paired_color_mask.spv"
        shader_path.write_text(shader, encoding="utf-8")
        result = subprocess.run(
            [validator, "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(spirv_path), str(shader_path)],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            raise AssertionError(f"GLSL rejected the directional warp gate:\n{result.stdout}\n{result.stderr}")
        assert spirv_path.is_file() and spirv_path.stat().st_size > 0
    print("FongMi paired-color and directional warp gate GLSL compiled to Vulkan SPIR-V")


def verify_reprepare_cycle(source: str) -> None:
    """Model the build script's second prepare call without changing synthesis."""
    base_source = PATCHER.unpatch_source(source)
    antialias_enabled = DOWNSAMPLE.has_antialias_filter(base_source)
    with tempfile.TemporaryDirectory(prefix="fongmi-reprepare-cycle-") as tmp:
        motion_path = Path(tmp) / "motion.c"
        motion_path.write_text(source, encoding="utf-8")
        assert PATCHER.strip_patch(motion_path), "the prior prepare pass must be stripped"
        DOWNSAMPLE.apply_patch(motion_path, enabled=antialias_enabled)
        assert PATCHER.apply_patch(motion_path), "the repeated prepare pass must reapply the gate"
        assert motion_path.read_text(encoding="utf-8") == source, "reprepare must preserve the exact gate source"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, help="raw or prepared pinned FongMi motion.c")
    args = parser.parse_args()

    if args.source:
        original = args.source.read_text(encoding="utf-8")
    else:
        original = (
            "float conflict_weight = smoothstep(0.03, 0.15, color_error);\n"
            "                    vec2 paired_error = vec2(max(error0.r, max(error0.g, error0.b)),\n"
            "                                             max(error1.r, max(error1.g, error1.b)));\n"
            + PATCHER.ORIGINAL_BLOCK
            + "                    vec2 evidence = support * vec2(relative_fit(stationary_error, paired_error.x),\n"
            + PATCHER.VISIBILITY_ANCHOR
        )

    expected_base = PATCHER.unpatch_source(original) if PATCHER.MARKER in original else original
    patched, changed = PATCHER.transform_source(original)
    assert PATCHER.has_paired_color_mask(patched)
    assert patched.count(PATCHER.ARTIFACT_STRENGTH_BLOCK) == 1
    assert "local_warp_support = evidence * vec2(f.w, r.w);" in patched
    assert "visibility *= mix(vec2(1.0), local_warp_keep, conflict_weight);" in patched
    assert "path_disagreement" not in PATCHER.ARTIFACT_STRENGTH_BLOCK
    assert "local_motion_length = length(displacement1 - displacement0)" not in patched
    assert "path_disagreement = length(displacement1 - displacement0)" not in patched
    patched_again, changed_again = PATCHER.transform_source(patched)
    assert not changed_again and patched_again == patched, "mask patch must be idempotent"

    legacy = patched.replace(PATCHER.ARTIFACT_STRENGTH_BLOCK, "", 1)
    assert PATCHER.has_legacy_paired_color_mask(legacy), "the paired-color-only mask must remain recognizable"
    upgraded, upgraded_changed = PATCHER.transform_source(legacy)
    assert upgraded_changed and upgraded == patched, "the paired-color-only mask must upgrade to the directional gate"
    assert PATCHER.unpatch_source(legacy) == expected_base

    prior_blocks = (
        PATCHER.ARTIFACT_STRENGTH_V1_BLOCK,
        PATCHER.ARTIFACT_STRENGTH_V2_BLOCK,
        PATCHER.ARTIFACT_STRENGTH_V3_BLOCK,
    )
    for prior_block in prior_blocks:
        prior = patched.replace(PATCHER.ARTIFACT_STRENGTH_BLOCK, prior_block, 1)
        assert PATCHER.has_paired_color_mask(prior), "each shipped prior gate must remain recognizable"
        upgraded_prior, changed_prior = PATCHER.transform_source(prior)
        assert changed_prior and upgraded_prior == patched, "each prior gate must upgrade in place"
        assert PATCHER.unpatch_source(prior) == expected_base

    unpatched = PATCHER.unpatch_source(patched)
    repatched, repatched_changed = PATCHER.transform_source(unpatched)
    assert repatched_changed and repatched == patched, "the patch must change only the supported source blocks"
    assert unpatched == expected_base, "unpatching must restore the exact pre-mask source"
    if args.source:
        verify_reprepare_cycle(patched)

    # Keep close color witnesses, but attenuate their paired recovery sooner.
    assert math.isclose(support(0.015, 0.03, 0.15), 1.0, abs_tol=1e-12)
    assert math.isclose(support(0.015, 0.02, 0.08), 1.0, abs_tol=1e-12)
    assert support(0.04, 0.02, 0.08) < support(0.04, 0.03, 0.15)
    assert support(0.06, 0.02, 0.08) < support(0.06, 0.03, 0.15)
    assert support(0.08, 0.02, 0.08) == 0.0 < support(0.08, 0.03, 0.15)

    # Conflict-free samples are untouched; a reliable camera pan is not rejected
    # just because its opposing displacement vectors are large.
    assert directional_visibility_scale(0.0, 0.0, 0.0) == 1.0
    assert directional_visibility_scale(0.0, 0.0, 1.0) == 1.0
    assert directional_visibility_scale(1.0, 0.95, 0.95) == 1.0

    # Each direction is independently gated by its paired witness and cycle geometry.
    weak_forward = directional_visibility_scale(1.0, 0.05, 1.0)
    strong_reverse = directional_visibility_scale(1.0, 0.95, 0.95)
    assert weak_forward == 0.0 and strong_reverse == 1.0
    assert math.isclose(directional_visibility_scale(0.5, 0.0, 1.0), 0.5, abs_tol=1e-12)
    assert math.isclose(directional_visibility_scale(1.0, 0.35, 1.0), 0.5, abs_tol=1e-12)
    assert directional_visibility_scale(1.0, 1.0, 0.0) == 0.0
    assert directional_visibility_scale(1.0, 2.0, 1.0) == 1.0
    assert directional_visibility_scale(1.0, -1.0, 1.0) == 0.0
    assert directional_visibility_scale(1.0, math.nan, 1.0) == 0.0
    assert directional_visibility_scale(1.0, math.inf, 1.0) == 0.0
    assert directional_visibility_scale(1.0, 1.0, math.inf) == 0.0

    # The asymmetric x/y inputs follow f.w/r.w without swapping source lanes.
    forward_keep = directional_visibility_scale(1.0, 0.95, 0.95)
    reverse_keep = directional_visibility_scale(1.0, 0.05, 0.95)
    assert (forward_keep, reverse_keep) == (1.0, 0.0)
    forward_keep = directional_visibility_scale(1.0, 0.05, 0.95)
    reverse_keep = directional_visibility_scale(1.0, 0.95, 0.95)
    assert (forward_keep, reverse_keep) == (0.0, 1.0)

    for conflict in (i / 100.0 for i in range(101)):
        previous_evidence_scale = -1.0
        for evidence_value in (i / 100.0 for i in range(101)):
            opacity = directional_visibility_scale(conflict, evidence_value, 0.9)
            assert 0.0 <= opacity <= 1.0
            assert opacity + 1e-12 >= previous_evidence_scale
            previous_evidence_scale = opacity
        for evidence_value in (0.0, 0.2, 0.6, 1.0):
            previous_conflict_scale = 1.0
            for conflict_value in (i / 100.0 for i in range(101)):
                opacity = directional_visibility_scale(conflict_value, evidence_value, 0.9)
                assert opacity <= previous_conflict_scale + 1e-12
                previous_conflict_scale = opacity
    for evidence_value in (0.0, 0.2, 0.6, 1.0):
        previous_geometry_scale = -1.0
        for geometry in (i / 100.0 for i in range(101)):
            opacity = directional_visibility_scale(1.0, evidence_value, geometry)
            assert opacity + 1e-12 >= previous_geometry_scale
            previous_geometry_scale = opacity

    baseline, warped0, warped1 = 0.4, 0.95, 0.2
    visibility0, visibility1 = 0.25, 0.25
    gate0, gate1 = weak_forward, strong_reverse
    result = (
        baseline * (1.0 - visibility0 * gate0 - visibility1 * gate1)
        + warped0 * visibility0 * gate0
        + warped1 * visibility1 * gate1
    )
    expected_one_lane = baseline * (1.0 - visibility1) + warped1 * visibility1
    assert math.isclose(result, expected_one_lane, abs_tol=1e-12)

    compile_mask_statement(patched)
    print(
        "FongMi mask passed: clean and coherent camera motion are unchanged; "
        "conflicting warp lanes are independently gated by paired evidence and cycle geometry; "
        "V1/V2/V3 upgrades and source reprepare are idempotent"
    )


if __name__ == "__main__":
    main()
