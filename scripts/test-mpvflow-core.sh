#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp "${TMPDIR:-/tmp}/mpvflow-core-test.XXXXXX")"
trap 'rm -f "$OUT"' EXIT
cc -std=c11 -O2 -Wall -Wextra -Werror \
  -I"$ROOT/app/src/main/cpp/mpvflow" \
  "$ROOT/app/src/main/cpp/mpvflow/mpvflow_core.c" \
  "$ROOT/scripts/tests/mpvflow_core_test.c" \
  -pthread -lm -o "$OUT"
"$OUT"
python3 "$ROOT/scripts/tests/mpvflow_gpu_shader_test.py"
python3 "$ROOT/scripts/tests/media3flow_gpu_shader_test.py"
python3 "$ROOT/scripts/tests/mpvflow_diagnostics_test.py"
python3 "$ROOT/scripts/tests/mpvflow_native_build_test.py"
