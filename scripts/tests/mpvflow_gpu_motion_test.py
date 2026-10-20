#!/usr/bin/env python3
"""Production matcher regressions for pan, layered motion, and parent fields.

Parent sentinels test coordinates at real search radii, not end-to-end video quality.
The layered probes intentionally exclude contour/occlusion and aperture-ambiguous
blocks; blend mode and playback FPS are independent of this matcher-only oracle.
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
    return "".join(ast.literal_eval(v) for v in re.findall(r'"(?:\\.|[^"\\])*"', match.group(1)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-vulkan", action="store_true")
    parser.add_argument("--baseline-revision", help="prove old exact-outline assertions fail this revision")
    parser.add_argument("--parent-baseline-revision", help="prove parent-coordinate assertions fail this revision")
    args = parser.parse_args()
    missing = [x for x in ("glslangValidator", "cc") if not shutil.which(x)]
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
    corrected = "vec2 parentOrigin=vec2(float(blockSize)*0.25-4.0)"
    for anchor in ("int sadAt(", "parentUniqueness=1.0", "float subpixelOffset(", corrected):
        if anchor not in text:
            raise SystemExit(f"Production shader_flow anchor missing: {anchor}")
    environment = os.environ.copy()
    environment["VK_ICD_FILENAMES"] = str(icds[0])
    with tempfile.TemporaryDirectory(prefix="mpvflow-motion-") as directory:
        directory = Path(directory)
        source, spirv, exe = directory / "flow.comp", directory / "flow.spv", directory / "motion"
        result = subprocess.run(["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", str(HARNESS), "-lvulkan", "-lm", "-o", str(exe)], capture_output=True, text=True)
        if result.returncode:
            raise SystemExit("Motion harness compilation failed:\n" + result.stdout + result.stderr)

        def compile_variant(variant: str, label: str) -> None:
            source.write_text(variant, encoding="utf-8")
            result = subprocess.run(["glslangValidator", "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(spirv), str(source)], capture_output=True, text=True)
            if result.returncode:
                raise SystemExit(f"{label} compilation failed:\n{result.stdout}{result.stderr}")

        def run(block: int, parent: bool, label: str, failure: str | None = None,
                mode: str | None = None) -> None:
            suffix = [mode] if mode else (["parent"] if parent else [])
            result = subprocess.run([str(exe), str(spirv), str(block)] + suffix, capture_output=True, text=True, env=environment)
            if failure is None:
                if result.returncode:
                    raise SystemExit(f"Motion fixture failed ({label}):\n{result.stdout}{result.stderr}")
                print(f"[{label}]\n" + result.stdout, end="")
            else:
                if result.returncode != 1 or failure not in result.stderr:
                    raise SystemExit(f"Negative control did not fail as expected ({label}):\n{result.stdout}{result.stderr}")
                print(f"[negative control caught: {label}]\n" + result.stderr, end="")

        compile_variant(text, "production")
        run(4, False, "original exact-outline pan")
        run(4, False, "layered fast foreground over shifted background", mode="layered")
        for block in (4, 8):
            run(block, True, f"block-{block} centered parent-field sentinel")
        guard = "if(centerCost==0) return 0.0;"
        if text.count(guard) != 1:
            raise SystemExit("Exact-match refinement guard missing or duplicated")
        compile_variant(text.replace("int radius=pc.cfg.z;", "int radius=1;", 1), "layered radius negative control")
        run(4, False, "layered radius negative control", "motion regression failed:", mode="layered")
        compile_variant(text.replace(guard, "", 1), "disabled exact-match preservation")
        run(4, False, "disabled exact-match preservation", "motion regression failed:")
        for replacement, blocks, label in (
            ("vec2 parentOrigin=vec2(0.0)", (4, 8), "old origin-only parent sampling"),
            ("vec2 parentOrigin=vec2(-2.0)", (4,), "wrong fixed -2 fine anchor"),
            ("vec2 parentOrigin=vec2(-3.0)", (8,), "wrong fixed -3 middle anchor"),
        ):
            compile_variant(text.replace(corrected, replacement, 1), label)
            for block in blocks:
                run(block, True, f"{label} block={block}", "parent_probe_bad=1")
        if args.parent_baseline_revision:
            previous = subprocess.check_output(["git", "show", f"{args.parent_baseline_revision}:app/src/main/cpp/mpvflow/mpvflow_gpu.c"], cwd=ROOT, text=True)
            compile_variant(shader(previous), "baseline parent mapping")
            for block in (4, 8):
                run(block, True, f"baseline parent mapping block={block}", "parent_probe_bad=1")
        if args.baseline_revision:
            previous = subprocess.check_output(["git", "show", f"{args.baseline_revision}:app/src/main/cpp/mpvflow/mpvflow_gpu.c"], cwd=ROOT, text=True)
            compile_variant(shader(previous), "baseline exact-outline motion")
            run(4, False, "baseline exact-outline motion", "motion regression failed:")


if __name__ == "__main__":
    main()
