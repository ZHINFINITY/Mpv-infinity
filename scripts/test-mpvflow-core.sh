#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp "${TMPDIR:-/tmp}/mpvflow-core-test.XXXXXX")"
OUT_OPENCL_VALIDATION="$(mktemp "${TMPDIR:-/tmp}/mpvflow-opencl-validation-test.XXXXXX")"
trap 'rm -f "$OUT" "$OUT_OPENCL_VALIDATION"' EXIT
cc -std=c11 -O2 -Wall -Wextra -Werror \
  -I"$ROOT/app/src/main/cpp/mpvflow" \
  "$ROOT/app/src/main/cpp/mpvflow/mpvflow_core.c" \
  "$ROOT/scripts/tests/mpvflow_core_test.c" \
  -pthread -lm -o "$OUT"
"$OUT"
cc -std=c11 -O2 -Wall -Wextra -Werror \
  -I"$ROOT/app/src/main/cpp/mpvflow" \
  "$ROOT/scripts/tests/mpvflow_opencl_validation_test.c" \
  -o "$OUT_OPENCL_VALIDATION"
"$OUT_OPENCL_VALIDATION"
python3 "$ROOT/scripts/tests/mpvflow_gpu_shader_test.py"
if [[ "${MPVFLOW_REQUIRE_VULKAN_PIXEL_TEST:-0}" == "1" ]]; then
  python3 "$ROOT/scripts/tests/mpvflow_gpu_synthesis_test.py" --require-vulkan
else
  python3 "$ROOT/scripts/tests/mpvflow_gpu_synthesis_test.py"
fi
python3 "$ROOT/scripts/tests/media3flow_gpu_shader_test.py"
python3 "$ROOT/scripts/tests/mpvflow_diagnostics_test.py"
python3 "$ROOT/scripts/tests/mpvflow_native_build_test.py"
