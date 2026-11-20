#!/usr/bin/env python3
"""Guard MPVFlow's Meson renderer-hook project-argument placement."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / "scripts/prepare-mpvflow.py"
sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("prepare_mpvflow", PREPARE)
assert spec and spec.loader, "could not load MPVFlow preparation module"
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)

marker = "# MPVFLOW_HAVE_RENDERER_HOOK"
fixture = "cc = meson.get_compiler('c')\nfeatures = {}\nlibrary('mpv', [])\n"
probe = prepare.RENDERER_HOOK_PROBE

placed = prepare.insert_project_arguments_before_first_target(fixture, probe)
assert placed.index(marker) < placed.index("library('mpv',"), (
    "renderer-hook project arguments must precede the first Meson target"
)
assert placed.count(marker) == 1, "renderer-hook probe must be inserted exactly once"
assert prepare.insert_project_arguments_before_first_target(placed, probe) == placed, (
    "renderer-hook probe insertion must be idempotent"
)
module_fixture = "cc = meson.get_compiler('c')\nfeatures = {}\nshared_module('helper', [])\n"
module_placed = prepare.insert_project_arguments_before_first_target(module_fixture, probe)
assert module_placed.index(marker) < module_placed.index("shared_module('helper',"), (
    "renderer-hook project arguments must precede shared-module targets too"
)

assigned_fixture = (
    "cc = meson.get_compiler('c')\n"
    "libplacebo = dependency('libplacebo')\n"
    "features = {'libplacebo': true}\n"
    "ebml_defs = custom_target('ebml_defs', [])\n"
    "libmpv = library('mpv', [])\n"
    "if rst2man.found()\n"
    "  custom_target('manpages', [])\n"
    "endif\n"
)
assigned_placed = prepare.insert_project_arguments_before_first_target(assigned_fixture, probe)
dependency_index = assigned_placed.index("libplacebo = dependency")
probe_index = assigned_placed.index(marker)
first_target_index = assigned_placed.index("ebml_defs = custom_target")
assert dependency_index < probe_index < first_target_index, (
    "renderer-hook probe must follow the libplacebo dependency and precede the first assigned target"
)
conditional_target_index = assigned_placed.index("custom_target('manpages'")
assert first_target_index < conditional_target_index, (
    "the assignment-form target must be selected before a later conditional bare target"
)
assert probe.count("dependencies: libplacebo") == 2, (
    "both renderer-hook header probes must use libplacebo's dependency include paths"
)

legacy_appended = fixture + "\n" + probe
relocated = prepare.insert_project_arguments_before_first_target(legacy_appended, probe)
assert relocated.index(marker) < relocated.index("library('mpv',"), (
    "an existing appended renderer-hook probe must be moved before the first target"
)
assert relocated.count(marker) == 1, "legacy probe relocation must leave exactly one probe"

try:
    prepare.insert_project_arguments_before_first_target("cc = meson.get_compiler('c')\n", probe)
except SystemExit:
    pass
else:
    raise AssertionError("missing target anchor must fail closed")

prepare_source = PREPARE.read_text(encoding="utf-8")
call = "insert_project_arguments_before_first_target(meson, renderer_hook_probe)"
assert prepare_source.count(call) == 2, "both MPV patch paths must use the verified pre-target insertion helper"
assert "meson += renderer_hook_probe" not in prepare_source, "probe must never be appended after targets"
