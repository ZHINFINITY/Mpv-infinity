#!/usr/bin/env python3
"""Execute the actual fine matcher on translated outlined shapes.

The oracle asserts that exact, correctly translated straight-edge blocks can
legitimately have zero uniqueness in both directions. It does not claim that
all independently moving layers or ambiguous repeated textures are recovered.
"""
from __future__ import annotations

import argparse
import ast
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c"
HARNESS = ROOT / "scripts/tests/mpvflow_gpu_motion_test.c"


def shader(text: str) -> str:
    match = re.search(r"static const char shader_flow\[\] =\n(.*?);\n", text, re.DOTALL)
    if not match:
        raise SystemExit("Could not find embedded shader_flow")
    return "".join(ast.literal_eval(value) for value in re.findall(r'"(?:\\.|[^"\\])*"', match.group(1)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-vulkan", action="store_true")
    parser.add_argument("--baseline-revision", help="also prove this commit fails exact-outline motion assertions")
    args = parser.parse_args()
    missing = [command for command in ("glslangValidator", "cc") if not shutil.which(command)]
    if not Path("/usr/include/vulkan/vulkan.h").is_file():
        missing.append("Vulkan headers")
    if not list(Path("/usr/lib").glob("**/libvulkan.so")):
        missing.append("Vulkan loader")
    icds = sorted(Path("/usr/share/vulkan/icd.d").glob("lvp_icd*.json"))
    if not icds:
        missing.append("lavapipe")
    if missing:
        message = "Vulkan motion oracle unavailable: " + ", ".join(missing)
        if args.require_vulkan:
            raise SystemExit(message)
        print(message + "; skipping optional local run")
        return

    text = shader(SOURCE.read_text(encoding="utf-8"))
    for anchor in ("int sadAt(", "parentUniqueness=1.0", "float subpixelOffset("):
        if anchor not in text:
            raise SystemExit(f"Production shader_flow anchor missing: {anchor}")
    environment = os.environ.copy()
    environment["VK_ICD_FILENAMES"] = str(icds[0])
    with tempfile.TemporaryDirectory(prefix="mpvflow-motion-") as directory:
        directory = Path(directory)
        source = directory / "flow.comp"
        spirv = directory / "flow.spv"
        executable = directory / "motion"
        source.write_text(text, encoding="utf-8")
        commands = (
            ("shader_flow compilation", ["glslangValidator", "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(spirv), str(source)]),
            ("motion harness compilation", ["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", str(HARNESS), "-lvulkan", "-lm", "-o", str(executable)]),
            ("Vulkan motion harness", [str(executable), str(spirv)]),
        )
        for label, command in commands:
            result = subprocess.run(command, capture_output=True, text=True, env=environment)
            if result.returncode:
                raise SystemExit(f"{label} failed:\n{result.stdout}{result.stderr}")
            if label == "Vulkan motion harness":
                print(result.stdout, end="")
        guard = "if(centerCost==0) return 0.0;"
        if text.count(guard) != 1:
            raise SystemExit("Exact-match refinement guard missing or duplicated")
        variants = [("disabled exact-match preservation", text.replace(guard, "", 1))]
        if args.baseline_revision:
            previous = subprocess.check_output(
                ["git", "show", f"{args.baseline_revision}:app/src/main/cpp/mpvflow/mpvflow_gpu.c"],
                cwd=ROOT, text=True)
            variants.append(("last-tested commit exact-outline regression", shader(previous)))
        for label, variant in variants:
            source.write_text(variant, encoding="utf-8")
            compiled = subprocess.run(commands[0][1], capture_output=True, text=True)
            if compiled.returncode:
                raise SystemExit(f"{label} compilation failed:\n{compiled.stdout}{compiled.stderr}")
            result = subprocess.run([str(executable), str(spirv)], capture_output=True, text=True, env=environment)
            if result.returncode != 1 or "motion regression failed:" not in result.stderr:
                raise SystemExit(f"Negative control did not fail as expected ({label}):\n{result.stdout}{result.stderr}")
            print(f"[negative control caught: {label}]\n{result.stderr}", end="")


if __name__ == "__main__":
    main()
