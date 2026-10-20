#!/usr/bin/env python3
"""Real Vulkan oracle for the embedded whole-pair ghost gate."""
from __future__ import annotations
import argparse, ast, os, re, shutil, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c"
HARNESS = Path(__file__).with_suffix(".c")

def embedded(name: str, source: str) -> str:
    """Decode one named C string containing an embedded gate shader."""
    m = re.search(rf"static const char {name}\[\] =\n(.*?);\n", source, re.S)
    if not m:
        raise SystemExit(f"missing embedded {name}")
    literals = re.findall(r'"(?:\\.|[^"\\])*"', m.group(1))
    if not literals:
        raise SystemExit(f"empty embedded {name}")
    return "".join(ast.literal_eval(x) for x in literals)

def require_vulkan() -> Path:
    """Require oracle tooling and return the installed lavapipe ICD file."""
    missing = [x for x in ("glslangValidator", "cc") if not shutil.which(x)]
    if not Path("/usr/include/vulkan/vulkan.h").is_file(): missing.append("Vulkan headers")
    if not any(Path("/usr/lib").glob("**/libvulkan.so*")): missing.append("Vulkan loader")
    icds = sorted(Path("/usr/share/vulkan/icd.d").glob("lvp_icd*.json"))
    if not icds: missing.append("lavapipe")
    if missing: raise SystemExit("pair-ghost Vulkan oracle unavailable: " + ", ".join(missing))
    return icds[0]

def main() -> None:
    """Compile the exact embedded shaders and execute their Vulkan oracle."""
    ap = argparse.ArgumentParser()
    ap.add_argument("--require-vulkan", action="store_true")
    args = ap.parse_args()
    required = args.require_vulkan or os.environ.get("MPVFLOW_REQUIRE_VULKAN_PIXEL_TEST") == "1"
    # This test is intentionally required in CI; a local non-required invocation may skip.
    try: icd = require_vulkan()
    except SystemExit:
        if required: raise
        print("pair-ghost Vulkan tooling unavailable; skipping optional local run")
        return
    source = SOURCE.read_text(encoding="utf-8")
    shaders = [embedded(x, source) for x in ("shader_pair_gate_first", "shader_pair_gate_reduce", "shader_pair_gate_merge")]
    assert "MPVFLOW_PAIR_GHOST_SPATIAL_GAP_V3" in shaders[0]
    assert "imageStore(outTex,tile+ivec2(0,tiles.y),spatial)" in shaders[0]
    assert "counts/float(sz.x*sz.y)" in shaders[0]
    assert "weak>=.10&&bad>=.01" in shaders[2]
    assert "scene.r<=.1882353" in shaders[2]
    assert "isnan(g)" in shaders[2]
    assert "scene.r>=.003&&weak>=.04&&weak<=.25&&supported>=.008" in shaders[2]
    assert "ivec2(0,1)" in shaders[2]
    with tempfile.TemporaryDirectory(prefix="pair-ghost-", dir=os.environ.get("TMPDIR") or "/dev/shm") as td:
        d = Path(td)
        spirv = []
        for i, text in enumerate(shaders):
            glsl = d / f"gate{i}.comp"; out = d / f"gate{i}.spv"
            glsl.write_text(text, encoding="utf-8")
            r = subprocess.run(["glslangValidator", "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(out), str(glsl)], text=True, capture_output=True)
            if r.returncode: raise SystemExit(f"GLSL gate{i} failed:\n{r.stdout}{r.stderr}")
            spirv.append(out)
        exe = d / "pair-ghost-vulkan"
        r = subprocess.run(["cc", "-std=gnu11", "-O2", "-Wall", "-Wextra", "-o", str(exe), str(HARNESS), "-lvulkan", "-lm"], text=True, capture_output=True)
        if r.returncode: raise SystemExit(f"pair-ghost harness build failed:\n{r.stdout}{r.stderr}")
        r = subprocess.run([str(exe), *map(str, spirv)], text=True, capture_output=True, env={**os.environ, "TMPDIR": str(d), "XDG_CACHE_HOME": str(d), "VK_ICD_FILENAMES": str(icd), "LP_NUM_THREADS": "2"})
        print(r.stdout, end="")
        if r.returncode: raise SystemExit(r.stderr or "pair-ghost Vulkan harness failed")
    print("MPV Flow pair-ghost exact Vulkan gate checks passed")

if __name__ == "__main__": main()
