#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER = ROOT / "scripts" / "patch-rife-vulkan-frame-api.py"
SPEC = importlib.util.spec_from_file_location("rife_vulkan_patch", PATCHER)
if SPEC is None or SPEC.loader is None:
    raise SystemExit("could not load RIFE Vulkan patch helper")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

cpp = r"""
struct Tensor {
    int w = 64;
    int h = 48;
    int dims = 2;
    int c = 1;
    int elempack = 1;
    unsigned long elemsize = 3;
    bool empty() const { return false; }
};
struct Options { bool use_fp16_storage = true; bool use_int8_storage = true; };
struct Network { Options opt; };
static bool rife_vfi_gpu_input_layout_is_compatible(
    bool, bool, int, int, int, unsigned long) { return true; }
int main()
{
    const Tensor frame0;
    const Tensor frame1;
    const Tensor *gpu_in0 = &frame0;
    const Tensor *gpu_in1 = &frame1;
    int w = gpu_in0->w;
    int h = gpu_in0->h;
    Network flownet;
""" + MODULE.gpu_tensor_guard() + r"""
    return 0;
}
"""

subprocess.run(
    ["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-fsyntax-only", "-x", "c++", "-"],
    input=cpp,
    text=True,
    check=True,
)
print("RIFE generated Vulkan input guard compiles")
