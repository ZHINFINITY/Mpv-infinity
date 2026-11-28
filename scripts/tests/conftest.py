"""Standalone CLI checks: several read argv, need external tools or patch sources at
import time, so they are excluded here and run as scripts, the way CI runs them."""

collect_ignore = [
    "motion_artifact_policy_test.py",
    "motion_camera_pan_zoom_shader_test.py",
    "mpvflow_arm64_workflow_test.py",
    "mpvflow_pinned_native_source_test.py",
]
