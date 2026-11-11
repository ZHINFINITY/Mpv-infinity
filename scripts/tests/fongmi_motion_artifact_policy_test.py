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
BASE_OUTPUT = """color = mix(mix(original0, original1, alpha),
             mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));"""
PINNED_OUTPUT = """return mix(mix(original0, original1, blend),
           mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));"""
LEGACY_TRUST = BASE_TRUST.replace(
    "            trust *= 1.0 - smoothstep(0.03, 0.15,\n                                      color_error);",
    "            // MPVFLOW_ARTIFACT_TRUST_TAPER_V2: soften color-conflict fallback only when motion trust remains\n"
    "            trust *= 1.0 - smoothstep(0.03, 0.30,\n                                      color_error);",
)

BASE_RECONSTRUCTION = """    if (!(evidence > best || (evidence == best && distance < nearest)))
        continue;"""


def fixture(trust: str = BASE_TRUST, output: str = BASE_OUTPUT, output_indent: str = "    ") -> str:
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
        + "\n"
        + PATCHER._indent_block(output, output_indent)
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


def local_motion_softness(
    trust: float,
    boundary_activation: float,
    bidirectional_support: float,
    local_quality: float,
    motion_length: float,
) -> float:
    return (
        0.24
        * (1.0 - min(1.0, max(0.0, trust)))
        * min(1.0, max(0.0, boundary_activation))
        * min(1.0, max(0.0, bidirectional_support))
        * smoothstep(0.45, 0.70, local_quality)
        * smoothstep(0.75, 3.0, motion_length)
    )


original = fixture()
patched, changed = PATCHER.transform_source(original)
assert changed
assert PATCHER.MARKER in patched
assert PATCHER.LOCAL_SOFTENING_MARKER in patched
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
assert "vec2 local_motion_delta = (r.xy - f.xy) * scale;" in patched
assert "min(0.75, 0.08 * local_motion_length)" in patched
assert "float local_motion_softness = 0.24 *" in patched
assert "smoothstep(0.45, 0.70, local_quality)" in patched
assert "smoothstep(0.75, 3.0, local_motion_length)" in patched
assert "sample_rgba(first, source_position + f.xy * scale + local_motion_offset)" in patched
assert "sample_rgba(second, source_position + r.xy * scale - local_motion_offset)" in patched
assert "vec4 source_blend = mix(original0, original1, alpha);" in patched
assert "color = mix(source_blend, mix(warped0, warped1, alpha), clamp(trust, 0.0, 1.0));" in patched
assert "FongMi synthesis prefix\n" in patched and "FongMi synthesis suffix\n" in patched
again, changed_again = PATCHER.transform_source(patched)
assert not changed_again and again == patched

pinned_patched, pinned_changed = PATCHER.transform_source(
    fixture(output=PINNED_OUTPUT, output_indent="            ")
)
assert pinned_changed and PATCHER._is_patched(pinned_patched)
assert "sample_rgba(source0, vec2(pixel) + f.xy * scale + local_motion_offset)" in pinned_patched
assert "sample_rgba(source1, vec2(pixel) + r.xy * scale - local_motion_offset)" in pinned_patched
assert "return mix(source_blend, mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));" in pinned_patched
assert "color = mix(source_blend" not in pinned_patched

# An existing V3 synthesis patch is upgraded in place, then remains idempotent.
old_reconstruction = PATCHER._indent_block(PATCHER.RECONSTRUCTION_BLOCK, "    ")
legacy_v3 = patched.replace(
    old_reconstruction,
    "    if (!(evidence > best || (evidence == best && distance < nearest)))",
    1,
)
legacy_v3 = legacy_v3.replace(PATCHER.MARKER, PATCHER.PREVIOUS_MARKER, 1)
legacy_v3 = legacy_v3.replace(
    PATCHER._indent_block(PATCHER.LEGACY_SOFTENED_OUTPUT_BLOCK, "    "),
    PATCHER._indent_block(BASE_OUTPUT, "    "),
    1,
)
legacy_v3 = legacy_v3.replace(
    PATCHER._indent_block(PATCHER.MOTION_SOFTNESS_CALC, "            ") + "\n",
    "",
    1,
)
legacy_v3 = legacy_v3.replace("            float local_quality = 1.0;\n", "", 1)
legacy_v3 = legacy_v3.replace("            local_quality = min(", "            float local_quality = min(", 1)
assert PATCHER._is_synthesis_patched_v3(legacy_v3)
upgraded_v3, upgraded_v3_changed = PATCHER.transform_source(legacy_v3)
assert upgraded_v3_changed and PATCHER._is_patched(upgraded_v3)
upgraded_v3_again, upgraded_v3_changed_again = PATCHER.transform_source(upgraded_v3)
assert not upgraded_v3_changed_again and upgraded_v3_again == upgraded_v3

# Existing V3 output in the pinned return-expression form upgrades as well.
pinned_v3 = pinned_patched.replace(
    PATCHER._indent_block(PATCHER.PINNED_SOFTENED_OUTPUT_BLOCK, "            "),
    PATCHER._indent_block(PINNED_OUTPUT, "            "),
    1,
)
pinned_v3 = pinned_v3.replace(PATCHER.MARKER, PATCHER.PREVIOUS_MARKER, 1)
pinned_v3 = pinned_v3.replace(
    PATCHER._indent_block(PATCHER.MOTION_SOFTNESS_CALC, "            ") + "\n",
    "",
    1,
)
pinned_v3 = pinned_v3.replace("            float local_quality = 1.0;\n", "", 1)
pinned_v3 = pinned_v3.replace("            local_quality = min(", "            float local_quality = min(", 1)
assert PATCHER._is_synthesis_patched_v3(pinned_v3)
pinned_upgraded, pinned_upgraded_changed = PATCHER.transform_source(pinned_v3)
assert pinned_upgraded_changed and PATCHER._is_patched(pinned_upgraded)
assert "return mix(source_blend, mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));" in pinned_upgraded

# An existing V2-only build tree is upgraded, not mistaken for stock source.
upgraded_v2, upgraded_v2_changed = PATCHER.transform_source(fixture(LEGACY_TRUST))
assert upgraded_v2_changed and PATCHER.MARKER in upgraded_v2
assert PATCHER.LEGACY_MARKER not in upgraded_v2
assert PATCHER.LOCAL_SOFTENING_MARKER in upgraded_v2

for invalid in (
    "unrecognized FongMi source",
    fixture() + fixture(),
    fixture(LEGACY_TRUST.replace("color-conflict fallback only when motion trust remains", "unexpected")),
    patched.replace("float chroma_error =", "float chroma_error_missing ="),
    patched.replace(PATCHER.LOCAL_SOFTENING_MARKER, "unexpected-local-softening-marker"),
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

assert local_motion_softness(0.0, 1.0, 1.0, 1.0, 20.0) == 0.24
assert local_motion_softness(0.5, 1.0, 1.0, 1.0, 20.0) == 0.12
assert local_motion_softness(1.0, 1.0, 1.0, 1.0, 20.0) == 0.0
assert local_motion_softness(0.0, 1.0, 1.0, 1.0, 0.0) == 0.0, "static content must not soften"
assert local_motion_softness(0.0, 0.0, 1.0, 1.0, 20.0) == 0.0, "clean regions must not soften"
assert local_motion_softness(0.0, 1.0, 0.0, 1.0, 20.0) == 0.0, "unsupported vectors must retain source fallback"
assert local_motion_softness(0.0, 1.0, 1.0, 0.0, 20.0) == 0.0, "incoherent neighborhoods must not smear"
for quality in (i / 100.0 for i in range(101)):
    for motion in (i / 10.0 for i in range(51)):
        weight = local_motion_softness(0.0, 1.0, 1.0, quality, motion)
        assert 0.0 <= weight <= 0.24

retained_source = (ROOT / "app/src/main/cpp/mpvflow/fongmi/motion.c").read_text(encoding="utf-8")
retained_result, retained_changed = PATCHER.transform_source(retained_source)
assert not retained_changed and retained_result == retained_source, "retained snapshot should mirror active V4 and reconstruction safeguards"
if args.source:
    active_source = args.source.read_text(encoding="utf-8")
    active_result, active_changed = PATCHER.transform_source(active_source)
    assert not active_changed and active_result == active_source, "active pinned shader must contain the verified V4 patch exactly once"
    print("Active pinned FongMi synthesis and reconstruction sources contain the verified V4 softening policy")
print(
    "FongMi artifact policy passed: max taper trust step "
    f"{old_step:.4f} -> {new_step:.4f}; local directional softness is capped at 0.24 "
    "and gated by motion, bidirectional support, boundary and neighborhood coherence"
)
