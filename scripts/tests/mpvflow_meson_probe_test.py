#!/usr/bin/env python3
"""Guard FongMi source preparation against the retired Meson renderer-hook probe."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")

# #140 intentionally keeps the native FongMi runtime path and no longer patches
# upstream MPV's Meson project to probe an app-owned renderer hook.
for retired_marker in (
    "RENDERER_HOOK_PROBE",
    "MPVFLOW_HAVE_RENDERER_HOOK",
    "insert_project_arguments_before_first_target",
    "renderer_hook_probe",
):
    assert retired_marker not in PREPARE, (
        f"retired renderer-hook Meson probe was reintroduced: {retired_marker}"
    )

print("FongMi MPV preparation remains free of the retired Meson renderer-hook probe")
