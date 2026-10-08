#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cat >"$TMP/test.c" <<'C'
#include "rife_vfi_process_dimensions.h"
#include <assert.h>

static void expect_dimensions(int source_width, int source_height,
                              int max_dimension, int target_fps,
                              int expected_width, int expected_height)
{
    int input_width = 0, input_height = 0;
    int output_width = 0, output_height = 0;
    assert(rife_vfi_process_dimensions(source_width, source_height,
                                       max_dimension, target_fps,
                                       &input_width, &input_height));
    // Input-import and output-ring sizing must resolve the same policy.
    assert(rife_vfi_process_dimensions(source_width, source_height,
                                       max_dimension, target_fps,
                                       &output_width, &output_height));
    assert(input_width == output_width && input_height == output_height);
    assert(input_width == expected_width && input_height == expected_height);
}

int main(void)
{
    // Auto mode: the cap follows the requested interpolation rate.
    expect_dimensions(1920, 1080, 0, 60, 480, 270);
    expect_dimensions(1920, 1080, 0, 48, 720, 404);
    expect_dimensions(1920, 1080, 0, 30, 1080, 606);
    expect_dimensions(1080, 1920, 0, 60, 270, 480);
    expect_dimensions(320, 180, 0, 60, 320, 180);

    // An explicit cap works independently of target FPS.
    expect_dimensions(1920, 1080, 720, 60, 720, 404);
    expect_dimensions(1920, 1080, 800, 30, 800, 450);

    // -1 preserves source resolution; invalid inputs fail without dimensions.
    expect_dimensions(1920, 1080, -1, 60, 1920, 1080);
    assert(!rife_vfi_process_dimensions(0, 1080, 0, 60, 0, 0));
    assert(!rife_vfi_process_dimensions(1920, 1080, -2, 60, 0, 0));
}
C
cc -std=c11 -Wall -Wextra -Werror \
  -I"$ROOT/app/src/main/cpp/rife" "$TMP/test.c" -o "$TMP/test"
"$TMP/test"
echo "RIFE shared processing-dimensions tests passed"
