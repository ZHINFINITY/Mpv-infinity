#!/usr/bin/env python3
"""Tests for the app-owned patch applied to the pinned FongMi shader."""
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


def smoothstep(edge0: float, edge1: float, value: float) -> float:
    t = min(1.0, max(0.0, (value - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def trust_weight(upper_edge: float, color_error: float) -> float:
    return 1.0 - smoothstep(0.03, upper_edge, color_error)


source = "FongMi synthesis prefix\n" + PATCHER.ORIGINAL_TAPER + "\nFongMi synthesis suffix\n"
patched, changed = PATCHER.transform_source(source)
assert changed
assert PATCHER.MARKER in patched
assert "smoothstep(0.03, 0.30," in patched
assert "smoothstep(0.03, 0.15," not in patched
assert "smoothstep(0.03, 0.22," not in patched
assert "FongMi synthesis prefix\n" in patched and "FongMi synthesis suffix\n" in patched
again, changed_again = PATCHER.transform_source(patched)
assert not changed_again and again == patched

for invalid in (
    "unrecognized FongMi source",
    PATCHER.ORIGINAL_TAPER + "\n" + PATCHER.ORIGINAL_TAPER,
    PATCHER.PATCHED_TAPER + "\n" + PATCHER.ORIGINAL_TAPER,
):
    try:
        PATCHER.transform_source(invalid)
    except ValueError:
        pass
    else:
        raise AssertionError("the patcher must reject unknown or ambiguous source")

# Synthetic high-trust texture-conflict ramp: widening the fade from .22 to .30
# lowers its largest per-pixel trust step while retaining full trust at low error
# and a complete fallback by the new upper edge. This is a shader-policy proxy,
# not a claim of device-side visual improvement.
errors = [i / 255.0 for i in range(256)]
old = [trust_weight(0.22, value) for value in errors]
new = [trust_weight(0.30, value) for value in errors]
assert all(a >= b for a, b in zip(old, old[1:]))
assert all(a >= b for a, b in zip(new, new[1:]))
old_step = max(abs(a - b) for a, b in zip(old, old[1:]))
new_step = max(abs(a - b) for a, b in zip(new, new[1:]))
assert new_step < old_step, (old_step, new_step)
assert math.isclose(trust_weight(0.30, 0.03), 1.0, abs_tol=1e-9)
assert trust_weight(0.30, 0.30) == 0.0
assert trust_weight(0.30, 0.15) > trust_weight(0.22, 0.15)
assert trust_weight(0.30, 0.22) > trust_weight(0.22, 0.22)
if args.source:
    active_source = args.source.read_text(encoding="utf-8")
    assert active_source.count(PATCHER.MARKER) == 1, "the active pinned FongMi shader must contain the patch marker once"
    assert active_source.count(PATCHER.PATCHED_TAPER) == 1, "the active pinned FongMi shader must contain the widened taper once"
    assert PATCHER.ORIGINAL_TAPER not in active_source, "the original narrow taper must not remain in the active source"
    print("Active pinned FongMi synthesis source contains the verified taper patch")
print(f"FongMi artifact taper policy passed: max per-8-bit trust step {old_step:.4f} -> {new_step:.4f}")
