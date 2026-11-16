#!/usr/bin/env python3
"""Regression checks for the active FongMi paired-color and artifact-strength mask."""
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


def conflict_opacity(conflict_weight: float) -> float:
    """Mirror mix(1.0, 0.35, conflict_weight) in the active GLSL."""
    return 1.0 + (0.35 - 1.0) * conflict_weight


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
{PATCHER.PATCHED_BLOCK.rstrip()}
    float conflict_weight = 0.6;
    vec2 visibility = vec2(0.4);
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
            raise AssertionError(f"glslang rejected the paired-color mask:\n{result.stdout}\n{result.stderr}")
        assert spirv_path.is_file() and spirv_path.stat().st_size > 0
    print("FongMi paired-color and conflict-opacity GLSL compiled to Vulkan SPIR-V")


def verify_reprepare_cycle(source: str) -> None:
    """Model the build script's second prepare call without changing synthesis."""
    base_source = PATCHER.unpatch_source(source)
    antialias_enabled = DOWNSAMPLE.has_antialias_filter(base_source)
    with tempfile.TemporaryDirectory(prefix="fongmi-reprepare-cycle-") as tmp:
        motion_path = Path(tmp) / "motion.c"
        motion_path.write_text(source, encoding="utf-8")
        assert PATCHER.strip_patch(motion_path), "the prior prepare pass must be stripped"
        DOWNSAMPLE.apply_patch(motion_path, enabled=antialias_enabled)
        assert PATCHER.apply_patch(motion_path), "the repeated prepare pass must reapply the mask"
        assert motion_path.read_text(encoding="utf-8") == source, "reprepare must preserve the exact mask source"


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
    patched_again, changed_again = PATCHER.transform_source(patched)
    assert not changed_again and patched_again == patched, "mask patch must be idempotent"

    legacy = patched.replace(PATCHER.ARTIFACT_STRENGTH_BLOCK, "", 1)
    assert PATCHER.has_legacy_paired_color_mask(legacy), "the base-branch mask must remain recognizable"
    upgraded, upgraded_changed = PATCHER.transform_source(legacy)
    assert upgraded_changed and upgraded == patched, "the prior mask must upgrade to the opacity taper"

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

    # Clean pixels are byte-for-byte unchanged by this taper; maximum conflict
    # leaves 35% of the prior warped-color contribution, a 65% local reduction.
    assert math.isclose(conflict_opacity(0.0), 1.0, abs_tol=1e-12)
    assert math.isclose(conflict_opacity(0.5), 0.675, abs_tol=1e-12)
    assert math.isclose(conflict_opacity(1.0), 0.35, abs_tol=1e-12)
    for conflict in (i / 100.0 for i in range(101)):
        assert 0.35 <= conflict_opacity(conflict) <= 1.0
        if conflict < 1.0:
            assert conflict_opacity(conflict + 0.01) <= conflict_opacity(conflict)
    baseline, warped, visibility = 0.4, 0.95, 0.8
    clean_result = baseline + (warped - baseline) * visibility * conflict_opacity(0.0)
    conflicted_result = baseline + (warped - baseline) * visibility * conflict_opacity(1.0)
    assert math.isclose(clean_result - baseline, (warped - baseline) * visibility, abs_tol=1e-12)
    assert math.isclose(conflicted_result - baseline, 0.35 * (warped - baseline) * visibility, abs_tol=1e-12)

    compile_mask_statement(patched)
    print(
        "FongMi mask passed: clean witnesses and zero-conflict pixels are unchanged; "
        "paired mismatch recovery is tapered earlier; high-conflict warped-color "
        "contribution is reduced by 65%; source upgrade and reprepare are idempotent"
    )


if __name__ == "__main__":
    main()
