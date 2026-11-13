#!/usr/bin/env python3
"""Compile the app-owned FongMi pan/zoom compute shader with glslang."""
from __future__ import annotations

import importlib.util
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER_PATH = ROOT / "scripts/patch_fongmi_motion_artifact.py"
SPEC = importlib.util.spec_from_file_location("camera_motion_patcher", PATCHER_PATH)
assert SPEC and SPEC.loader
PATCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCHER)

header = re.search(
    r"static const char camera_fit_header\[\] = GLSL\(\n(.*?)\n\);",
    PATCHER.CAMERA_FIT_SHADER,
    re.DOTALL,
)
body = re.search(
    r"static const char camera_fit_body\[\] = GLSL\(\n(.*?)\n\);",
    PATCHER.CAMERA_FIT_SHADER,
    re.DOTALL,
)
assert header and body, "both camera-fit GLSL sections must be present in the versioned patcher"

shader = f"""#version 450
layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
layout(set = 0, binding = 0) uniform sampler2D forward;
layout(set = 0, binding = 1) uniform sampler2D backward;
layout(set = 0, binding = 2) uniform sampler2D image;
layout(rgba16f, set = 0, binding = 3) writeonly uniform image2D out_camera;
{header.group(1)}
void main()
{{
{body.group(1)}
}}
"""

with tempfile.TemporaryDirectory(prefix="fongmi-camera-pan-zoom-") as directory:
    source = Path(directory) / "camera_pan_zoom.comp"
    source.write_text(shader, encoding="utf-8")
    subprocess.run(
        ["glslangValidator", "-V", "-S", "comp", str(source)],
        check=True,
        text=True,
    )
print("FongMi pan/zoom camera-fit compute shader compiled with glslang")
