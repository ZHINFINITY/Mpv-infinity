#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat >"$TMP/test.cpp" <<'CPP'
#include "rife_vfi_output_layout.h"
#include <cassert>
int main()
{
    using Layout = RifeVfiOutputLayout;
    assert(rife_vfi_output_layout_classify(2, 1, 1, 1, 3) == Layout::packed_rgb8);
    assert(rife_vfi_output_layout_classify(3, 1, 3, 1, 4) == Layout::planar_rgb32f);
    // FP16/int8 capability combinations do not change the output buffer ABI.
    assert(rife_vfi_output_layout_classify(2, 1, 1, 1, 2) == Layout::unsupported);
    assert(rife_vfi_output_layout_classify(3, 1, 3, 1, 2) == Layout::unsupported);
    assert(rife_vfi_output_layout_classify(2, 1, 3, 1, 3) == Layout::unsupported);
    assert(rife_vfi_output_layout_classify(2, 1, 1, 4, 3) == Layout::unsupported);
    assert(rife_vfi_output_layout_classify(3, 2, 3, 1, 4) == Layout::unsupported);
    assert(rife_vfi_output_layout_classify(3, 1, 1, 1, 4) == Layout::unsupported);
}
CPP
g++ -std=c++17 -Wall -Wextra -Werror \
  -I"$ROOT/app/src/main/cpp/rife" "$TMP/test.cpp" -o "$TMP/test"
"$TMP/test"
echo "RIFE Vulkan output layout tests passed"
