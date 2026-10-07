#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATOR="${GLSLANG_VALIDATOR:-glslangValidator}"
command -v "$VALIDATOR" >/dev/null 2>&1 || {
  echo "glslangValidator is required to validate the RIFE conversion shaders" >&2
  exit 1
}

BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rife-shader-test.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT

for shader in \
  "$ROOT/app/src/main/cpp/rife/rife_vfi_pack_rgb.comp" \
  "$ROOT/app/src/main/cpp/rife/rife_vfi_rgba_output.comp" \
  "$ROOT/app/src/main/cpp/rife/rife_vfi_rgba_output_fp32.comp"; do
  output="$BUILD_DIR/$(basename "$shader").spv"
  "$VALIDATOR" -V --target-env vulkan1.1 -S comp -o "$output" "$shader"
done

echo "RIFE Vulkan conversion shaders compile to SPIR-V 1.3"
