#!/usr/bin/env python3
"""Regression tests for the controlled FongMi motion-resolution patch."""
from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER_PATH = ROOT / "scripts/patch_fongmi_motion_resolution.py"
SPEC = importlib.util.spec_from_file_location("fongmi_motion_resolution", PATCHER_PATH)
assert SPEC and SPEC.loader
PATCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCHER)

VO = '''        p->motion = mp_motion_create(p->gpu, 480);
        MP_INFO(vo, "GPU motion interpolation enabled: target=%d fps, "
                    "analysis<=480 pixels, synthesis=source resolution.\\n", fps);
'''
MOTION = '''#define LEVELS 3
#define MATCH_RADIUS 6
#define MATCH_GROUP_SIZE 64

static bool analysis_passes(struct mp_motion *m)
{
    if (level == 0 && scale < 0.666667)
        spacing = 3;
}
'''

patched_vo, vo_changed = PATCHER.transform_vo(VO)
assert vo_changed
assert PATCHER.CAP_MARKER in patched_vo
assert PATCHER.NEW_CALL in patched_vo and PATCHER.NEW_LOG in patched_vo
assert PATCHER.OLD_CALL not in patched_vo and PATCHER.OLD_LOG not in patched_vo
patched_vo_again, vo_changed_again = PATCHER.transform_vo(patched_vo)
assert not vo_changed_again and patched_vo_again == patched_vo

patched_motion, motion_changed = PATCHER.transform_motion(MOTION)
assert motion_changed
assert PATCHER.PYRAMID_MARKER in patched_motion
assert "#define LEVELS 4" in patched_motion
assert "#define MATCH_RADIUS 6" in patched_motion
assert PATCHER.GRID_CONDITION in patched_motion
patched_motion_again, motion_changed_again = PATCHER.transform_motion(patched_motion)
assert not motion_changed_again and patched_motion_again == patched_motion

with tempfile.TemporaryDirectory(prefix="fongmi-motion-resolution-test-") as tmp:
    root = Path(tmp)
    (root / "video/out/gpu_next").mkdir(parents=True)
    (root / "video/out/vo_gpu_next.c").write_text(VO, encoding="utf-8")
    (root / "video/out/gpu_next/motion.c").write_text(MOTION, encoding="utf-8")
    PATCHER.patch_tree(root)
    assert (root / "video/out/vo_gpu_next.c").read_text(encoding="utf-8") == patched_vo
    assert (root / "video/out/gpu_next/motion.c").read_text(encoding="utf-8") == patched_motion
    PATCHER.patch_tree(root)

print("FongMi 960px resolution patch tests passed: caller, log, four-level range, idempotence")
