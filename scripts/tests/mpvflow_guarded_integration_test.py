#!/usr/bin/env python3
"""Protected compile/source controls for the opt-in guarded native integration."""
from __future__ import annotations
import ast
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c"


def embedded(text: str, name: str) -> str:
    match = re.search(rf"static const char {name}\[\] =\n(.*?);\n", text, re.S)
    if not match:
        raise SystemExit(f"missing embedded {name}")
    return "".join(ast.literal_eval(x) for x in re.findall(r'"(?:\\.|[^"\\])*"', match.group(1)))


def compile_glsl(source: str, path: Path, label: str) -> None:
    path.write_text(source, encoding="utf-8")
    out = path.with_suffix(".spv")
    result = subprocess.run(
        ["glslangValidator", "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(out), str(path)],
        capture_output=True, text=True,
    )
    if result.returncode:
        raise SystemExit(f"{label} failed to compile:\n{result.stdout}{result.stderr}")


def compile_negative(source: str, path: Path, label: str) -> None:
    path.write_text(source, encoding="utf-8")
    result = subprocess.run(
        ["glslangValidator", "-V", "--target-env", "vulkan1.2", "-S", "comp", str(path)],
        capture_output=True, text=True,
    )
    if result.returncode == 0:
        raise SystemExit(f"negative control unexpectedly compiled: {label}")


def main() -> None:
    if not shutil.which("glslangValidator"):
        print("glslangValidator unavailable; guarded native shader controls skipped")
        return
    text = SOURCE.read_text(encoding="utf-8")
    production = embedded(text, "shader_synthesize")
    guarded = embedded(text, "shader_guarded_synthesize")
    candidate = embedded(text, "shader_candidate_prepare")
    required = (
        "ctx->pass_synthesize = make_pass(gpu, shader_synthesize, synth_desc, 8);",
        "if (ctx->experimental_guarded_roots)",
        "ctx->pass_guarded_synthesize = make_pass(gpu, shader_guarded_synthesize, synth_desc_guarded, 10);",
        "ctx->pass_candidate_prepare = make_pass(gpu, shader_candidate_prepare, candidate_desc, 4);",
        "ctx->pass_synthesize_rgba16f = make_pass(gpu, rgba16f_shader, synth_desc, 8);",
        "ctx->pass_guarded_synthesize_rgba16f = make_pass(",
        "prepare_guarded_tables(ctx, pair, cfg, t)",
    )
    for needle in required:
        if needle not in text:
            raise SystemExit(f"missing guarded integration control: {needle}")
    if text.count("ctx->pass_candidate_prepare = make_pass") != 1:
        raise SystemExit("candidate pass is not strictly opt-in")
    if "pass_synthesize_rgba16f = make_pass(gpu, rgba16f_shader, synth_desc_guarded" in text:
        raise SystemExit("default RGBA16F path still uses guarded descriptors")
    with tempfile.TemporaryDirectory(prefix="mpvflow-guarded-shaders-") as directory:
        directory = Path(directory)
        compile_glsl(production, directory / "production_rgba8.comp", "production rgba8")
        compile_glsl(production.replace("layout(set=0,binding=7,rgba8)", "layout(set=0,binding=7,rgba16f)"), directory / "production_rgba16f.comp", "production rgba16f")
        compile_glsl(guarded, directory / "guarded_rgba8.comp", "guarded rgba8")
        compile_glsl(guarded.replace("layout(set=0,binding=7,rgba8)", "layout(set=0,binding=7,rgba16f)"), directory / "guarded_rgba16f.comp", "guarded rgba16f")
        compile_glsl(candidate, directory / "candidate_prepare.comp", "candidate prepare")
        # Protected negative control: a candidate output declared with the
        # wrong image type must not silently pass the integrated fixture.
        bad = candidate.replace("layout(set=0,binding=2,rgba32ui) writeonly uniform uimage2D candidateOut;",
                               "layout(set=0,binding=2,rgba32f) writeonly uniform uimage2D candidateOut;", 1)
        if bad == candidate:
            raise SystemExit("candidate format negative control did not apply")
        compile_negative(bad, directory / "candidate_prepare_invalid.comp", "candidate image format")
        print("guarded native shader controls: production/guarded rgba8+rgba16f and candidate prepare compiled; invalid candidate rejected")


if __name__ == "__main__":
    main()
