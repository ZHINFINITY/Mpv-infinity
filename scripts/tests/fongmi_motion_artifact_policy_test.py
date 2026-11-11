#!/usr/bin/env python3
"""Tests for the app-owned safeguards applied to the pinned FongMi shader."""
from __future__ import annotations

import argparse
import importlib.util
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER_PATH = ROOT / "scripts/patch_fongmi_motion_artifact.py"
SPEC = importlib.util.spec_from_file_location("patch_fongmi_motion_artifact", PATCHER_PATH)
assert SPEC and SPEC.loader
PATCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCHER)
parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path, help="active pinned FongMi motion.c built by the runtime workflow")
args = parser.parse_args()

BASE_PIXEL_HEADER = """    vec4 synthesize_pixel(sampler2D source0, sampler2D source1, sampler2D warp0,
                          sampler2D warp1, sampler2D scene_map, ivec2 pixel,
                          vec2 output_extent, vec2 motion_extent, float blend, int hdr)
    {"""
BASE_TRUST = """            vec3 conflict = abs(metric0 - metric1);
            float color_error = max(conflict.r, max(conflict.g, conflict.b));
            // An edge can constrain motion without a unique tangential match.
            // Recover it only when both directions and full-size colors agree.
            float reliable = min(f.w, r.w) *
                             (1.0 - smoothstep(0.002, 0.02, color_error));
            float trust = max(min(f.z, r.z), reliable);
            trust *= 1.0 - smoothstep(0.03, 0.15,
                                      color_error);
            if (isnan(trust) || isinf(trust))
                trust = 0.0;"""
LEGACY_TRUST = BASE_TRUST.replace(
    "            trust *= 1.0 - smoothstep(0.03, 0.15,\n                                      color_error);",
    "            // MPVFLOW_ARTIFACT_TRUST_TAPER_V2: soften color-conflict fallback only when motion trust remains\n"
    "            trust *= 1.0 - smoothstep(0.03, 0.30,\n                                      color_error);",
)

BASE_RECONSTRUCTION = """    if (!(evidence > best || (evidence == best && distance < nearest)))
        continue;"""


def fixture(trust: str = BASE_TRUST) -> str:
    return (
        "FongMi reconstruction prefix\n"
        "static const char reconstruction_body[] = GLSL(\n"
        + BASE_RECONSTRUCTION
        + "\n);\n"
        "FongMi synthesis prefix\n"
        "static const char synthesis_pixel_header[] = GLSL(\n"
        + BASE_PIXEL_HEADER
        + "\n"
        + trust
        + "\n);\n"
        "FongMi synthesis suffix\n"
    )


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    t = min(1.0, max(0.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def taper_weight(color_error: float) -> float:
    return 1.0 - smoothstep(0.03, 0.30, color_error)


def boundary_weight(
    luma_error: float,
    color_error: float,
    chroma_error: float,
    forward_quality: float = 1.0,
    backward_quality: float = 1.0,
    neighborhood_spread: float = 0.0,
) -> float:
    boundary = 1.0 - smoothstep(0.04, 0.08, luma_error)
    boundary *= 1.0 - smoothstep(0.08, 0.12, color_error) * smoothstep(0.03, 0.06, chroma_error)
    boundary *= 1.0 - (1.0 - smoothstep(0.04, 0.08, min(forward_quality, backward_quality))) * smoothstep(0.06, 0.10, color_error)
    activation = max(smoothstep(0.04, 0.06, luma_error), smoothstep(0.06, 0.08, color_error))
    if activation > 0.0:
        local_quality = 1.0 - smoothstep(0.65, 2.8, neighborhood_spread)
        boundary *= (1.0 - activation) + activation * smoothstep(0.45, 0.70, local_quality)
        boundary *= (1.0 - activation) + activation * smoothstep(0.30, 0.75, local_quality)
    return boundary


original = fixture()
patched, changed = PATCHER.transform_source(original)
assert changed
assert PATCHER.MARKER in patched
assert PATCHER.RECONSTRUCTION_MARKER in patched
assert "if (!(evidence > 0.0 &&" in patched
assert "if (!(evidence > best || (evidence == best && distance < nearest)))" not in patched
assert "neighborhood_quality(sampler2D field, vec2 uv, vec2 center)" in patched
assert "float luma_error = abs(dot(metric0," in patched
assert "float chroma_error = max(abs((metric0.r - metric0.g)" in patched
assert "smoothstep(0.03, 0.30," in patched
assert "smoothstep(0.03, 0.15," not in patched
assert "neighborhood_quality(warp0, uv, f.xy)" in patched
assert "neighborhood_quality(warp1, uv, r.xy)" in patched
assert "boundary_trust *= mix(1.0, smoothstep(0.45, 0.70, local_quality)" in patched
assert "region3_local_trust = mix(1.0, smoothstep(0.30, 0.75, local_quality)" in patched
assert "trust *= boundary_trust * region3_local_trust;" in patched
assert "FongMi synthesis prefix\n" in patched and "FongMi synthesis suffix\n" in patched
again, changed_again = PATCHER.transform_source(patched)
assert not changed_again and again == patched

# A source with the existing V3 synthesis patch gets the new reconstruction guard.
old_reconstruction = PATCHER._indent_block(PATCHER.RECONSTRUCTION_BLOCK, "    ")
legacy_v3 = patched.replace(
    old_reconstruction,
    "    if (!(evidence > best || (evidence == best && distance < nearest)))",
    1,
)
assert legacy_v3 != patched and PATCHER._is_synthesis_patched(legacy_v3)
repaired_v3, repaired_v3_changed = PATCHER.transform_source(legacy_v3)
assert repaired_v3_changed and repaired_v3 == patched

# An existing V2-only build tree is upgraded, not mistaken for stock source.
upgraded, upgraded_changed = PATCHER.transform_source(fixture(LEGACY_TRUST))
assert upgraded_changed and PATCHER.MARKER in upgraded
assert PATCHER.LEGACY_MARKER not in upgraded
assert "smoothstep(0.03, 0.30," in upgraded

for invalid in (
    "unrecognized FongMi source",
    fixture() + fixture(),
    fixture(LEGACY_TRUST.replace("color-conflict fallback only when motion trust remains", "unexpected")),
    patched.replace("float chroma_error =", "float chroma_error_missing ="),
):
    try:
        PATCHER.transform_source(invalid)
    except ValueError:
        pass
    else:
        raise AssertionError("the patcher must reject unknown, ambiguous, or inconsistent source")

# Shader-policy proxies only: they verify monotonic safeguards, not device-side visual results.
errors = [i / 255.0 for i in range(256)]
old = [1.0 - smoothstep(0.03, 0.22, value) for value in errors]
new = [taper_weight(value) for value in errors]
assert all(a >= b for a, b in zip(old, old[1:]))
assert all(a >= b for a, b in zip(new, new[1:]))
old_step = max(abs(a - b) for a, b in zip(old, old[1:]))
new_step = max(abs(a - b) for a, b in zip(new, new[1:]))
assert new_step < old_step, (old_step, new_step)
assert math.isclose(taper_weight(0.03), 1.0, abs_tol=1e-9)
assert taper_weight(0.30) == 0.0
assert taper_weight(0.15) > 1.0 - smoothstep(0.03, 0.22, 0.15)

assert math.isclose(boundary_weight(0.0, 0.0, 0.0), 1.0, abs_tol=1e-9)
assert boundary_weight(0.09, 0.09, 0.0) == 0.0, "strong luma boundary must fall back to source crossfade"
assert boundary_weight(0.0, 0.15, 0.09) == 0.0, "strong chroma conflict must fall back to source crossfade"
coherent = boundary_weight(0.05, 0.07, 0.0, neighborhood_spread=0.0)
divergent = boundary_weight(0.05, 0.07, 0.0, neighborhood_spread=2.8)
assert 0.0 <= divergent < coherent <= 1.0, (coherent, divergent)
assert math.isclose(taper_weight(0.15), 0.5829903978052126, abs_tol=1e-9)

retained_source = (ROOT / "app/src/main/cpp/mpvflow/fongmi/motion.c").read_text(encoding="utf-8")
retained_result, retained_changed = PATCHER.transform_source(retained_source)
assert not retained_changed and retained_result == retained_source, "retained snapshot should mirror the active V3 and reconstruction safeguards"
if args.source:
    active_source = args.source.read_text(encoding="utf-8")
    active_result, active_changed = PATCHER.transform_source(active_source)
    assert not active_changed and active_result == active_source, "active pinned shader must contain the verified V3 patch exactly once"
    print("Active pinned FongMi synthesis and reconstruction sources contain the verified artifact guards")
print(
    "FongMi artifact policy passed: max taper trust step "
    f"{old_step:.4f} -> {new_step:.4f}; local coherence and luma/chroma fallbacks are monotonic"
)
