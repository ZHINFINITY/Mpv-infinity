#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BINARY="$(mktemp)"
trap 'rm -f "$BINARY"' EXIT
cc -std=c11 -Wall -Wextra -Werror -pedantic \
  -I"$ROOT/app/src/main/cpp/rife" \
  "$ROOT/scripts/tests/rife_ahb_policy_test.c" \
  -o "$BINARY"
"$BINARY"
echo "RIFE AHardwareBuffer fail-closed policy tests passed"
