#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat >"$TMP/test.cpp" <<'CPP'
#include "rife_vfi_gpu_layout.h"
#include <cassert>

int main()
{
    // The pinned RIFE preprocessor's NCNN_int8_storage branch reads three
    // adjacent uint8 values per pixel from a 2-D VkMat.
    assert(rife_vfi_gpu_input_layout_is_compatible(true, true, 2, 1, 1, 3));
    assert(rife_vfi_gpu_input_layout_is_compatible(false, true, 2, 1, 1, 3));
    assert(!rife_vfi_gpu_input_layout_is_compatible(true, true, 3, 3, 1, 2));
    assert(!rife_vfi_gpu_input_layout_is_compatible(true, true, 2, 3, 1, 3));
    assert(!rife_vfi_gpu_input_layout_is_compatible(true, true, 2, 1, 4, 3));

    // Non-int8 model input is a planar three-channel fp16/fp32 VkMat.
    assert(rife_vfi_gpu_input_layout_is_compatible(true, false, 3, 3, 1, 2));
    assert(rife_vfi_gpu_input_layout_is_compatible(false, false, 3, 3, 1, 4));
    assert(!rife_vfi_gpu_input_layout_is_compatible(false, false, 2, 1, 1, 3));
}
CPP
g++ -std=c++17 -Wall -Wextra -Werror -I"$ROOT/app/src/main/cpp/rife" "$TMP/test.cpp" -o "$TMP/test"
"$TMP/test"
echo "RIFE Vulkan VkMat layout policy tests passed"
