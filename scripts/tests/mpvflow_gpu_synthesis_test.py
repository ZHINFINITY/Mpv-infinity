#!/usr/bin/env python3
"""Execute the embedded MPVFlow synthesis shader and check deterministic pixels."""
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
HARNESS = ROOT / "scripts/tests/mpvflow_gpu_synthesis_test.c"


def require_tooling(required: bool) -> bool:
    missing = []
    if not shutil.which("glslangValidator"):
        missing.append("glslangValidator")
    if not shutil.which("cc"):
        missing.append("C compiler")
    if not Path("/usr/include/vulkan/vulkan.h").is_file():
        missing.append("Vulkan development headers")
    if not any(Path("/usr/lib").glob("**/libvulkan.so")):
        missing.append("Vulkan loader/development library")
    icd_directory = Path("/usr/share/vulkan/icd.d")
    lavapipe_icds = sorted(icd_directory.glob("lvp_icd*.json"))
    if required and not lavapipe_icds:
        missing.append("lavapipe Vulkan software driver")
    if missing:
        message = "Vulkan synthesis pixel oracle unavailable: " + ", ".join(missing)
        if required:
            raise SystemExit(message)
        print(message + "; skipping optional local run")
        return False
    return True


def embedded_synthesis_shader() -> str:
    source = SOURCE.read_text(encoding="utf-8")
    match = re.search(
        r"static const char shader_synthesize\[\] =\n(.*?);\n",
        source,
        re.DOTALL,
    )
    if not match:
        raise SystemExit("Could not find embedded shader_synthesize")
    literals = re.findall(r'"(?:\\.|[^"\\])*"', match.group(1))
    if not literals:
        raise SystemExit("Embedded shader_synthesize has no C string literals")
    return "".join(ast.literal_eval(literal) for literal in literals)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--require-vulkan",
        action="store_true",
        help="fail instead of skipping when Vulkan execution dependencies are absent",
    )
    args = parser.parse_args()
    if not require_tooling(args.require_vulkan):
        return

    with tempfile.TemporaryDirectory(prefix="mpvflow-synthesis-oracle-") as temp:
        temp_path = Path(temp)
        shader = temp_path / "shader_synthesize.comp"
        spirv = temp_path / "shader_synthesize.spv"
        executable = temp_path / "mpvflow_gpu_synthesis_test"
        shader.write_text(embedded_synthesis_shader(), encoding="utf-8")
        compile_shader = subprocess.run(
            [
                "glslangValidator",
                "-V",
                "--target-env",
                "vulkan1.2",
                "-S",
                "comp",
                "-o",
                str(spirv),
                str(shader),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if compile_shader.returncode:
            raise SystemExit(
                "Embedded synthesis shader failed Vulkan compilation:\n"
                + compile_shader.stdout
                + compile_shader.stderr
            )
        compile_harness = subprocess.run(
            [
                "cc",
                "-std=c11",
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                str(HARNESS),
                "-lvulkan",
                "-o",
                str(executable),
            ],
            check=False,
            capture_output=True,
            text=True,
        )
        if compile_harness.returncode:
            raise SystemExit(
                "Vulkan pixel-oracle harness failed to compile:\n"
                + compile_harness.stdout
                + compile_harness.stderr
            )
        env = os.environ.copy()
        lavapipe_icds = sorted(Path("/usr/share/vulkan/icd.d").glob("lvp_icd*.json"))
        if lavapipe_icds:
            env["VK_ICD_FILENAMES"] = str(lavapipe_icds[0])
        for soft_blend, preserve_motion_blend, label in (
            ("0", "0", "default-off production output"),
            ("1", "0", "opt-in soft-blend output"),
            ("1", "1", "soft blend with stronger continuous-motion bias"),
        ):
            result = subprocess.run(
                [str(executable), str(spirv), soft_blend, preserve_motion_blend],
                check=False,
                capture_output=True,
                text=True,
                env=env,
            )
            if result.returncode:
                raise SystemExit(
                    f"Vulkan synthesis pixel oracle failed ({label}):\n"
                    + result.stdout
                    + result.stderr
                )
            print(f"[{label}]\n" + result.stdout, end="")


if __name__ == "__main__":
    main()
