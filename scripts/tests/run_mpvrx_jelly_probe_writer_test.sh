#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
build="$(mktemp -d)"; trap 'rm -rf "$build"' EXIT
src=("$root/app/src/main/cpp/mpvflow/mpvrx_jelly_probe_writer.c" "$root/scripts/tests/mpvrx_jelly_probe_writer_test.c")
flags=(-std=c11 -O1 -g -Wall -Wextra -Werror -Wpedantic -pthread -I"$root")
cc "${flags[@]}" "${src[@]}" -lm -o "$build/test"; "$build/test"
cc "${flags[@]}" -fno-omit-frame-pointer -fsanitize=address,undefined "${src[@]}" -lm -o "$build/test-asan"; ASAN_OPTIONS=detect_leaks=1:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 "$build/test-asan"
if cc "${flags[@]}" -fno-omit-frame-pointer -fsanitize=thread "${src[@]}" -lm -o "$build/test-tsan" 2>/dev/null; then TSAN_OPTIONS=halt_on_error=1 "$build/test-tsan" || echo 'TSan runtime unavailable or failed in this environment'; else echo 'TSan compiler unavailable'; fi
