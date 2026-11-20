#!/usr/bin/env python3
"""Adapt the app-owned GPU motion fixture to MPV 645 for host Vulkan CI only.

The tracked app fixture predates MPV 645's removal of the explicit source-pair
helpers. This adapter replaces only its scale test in a temporary MPV checkout;
no shipped runtime or tracked fixture source is changed.
"""
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
APP_FIXTURE = ROOT / "app/src/main/cpp/mpvflow/fongmi/test/gpu_motion.c"
MPV_REVISION = "645f8ecdd3c94379048e9ee3e7014e174de0bd02"
FUNCTION = re.compile(
    r"^static void scaled_source_pair\(pl_gpu gpu, struct mp_motion \*motion\)\n"
    r"\{.*?^\}",
    re.MULTILINE | re.DOTALL,
)

REPLACEMENT = r'''static void scaled_source_pair(pl_gpu gpu, struct mp_motion *motion)
{
    const int analysis_width = 128, analysis_height = 96;
    const int scales[] = {2, 4};

    for (int pass = 0; pass < 2; pass++) {
        int scale = scales[pass];
        int width = analysis_width * scale;
        int height = analysis_height * scale;
        size_t count = (size_t) width * height * 4;
        float *first = calloc(count, sizeof(float));
        float *second = calloc(count, sizeof(float));
        float *output = calloc(count, sizeof(float));
        assert(first && second && output);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sx = (x + 0.5f) / scale - 0.5f;
                float sy = (y + 0.5f) / scale - 0.5f;
                for (int c = 0; c < 4; c++) {
                    int index = (y * width + x) * 4 + c;
                    first[index] = c == 3 ? 1 : affine_signal(sx, sy, c, 0);
                    second[index] = c == 3 ? 1 : affine_signal(sx, sy, c, 1);
                }
            }
        }

        pl_tex a = upload_size(gpu, first, width, height);
        pl_tex b = upload_size(gpu, second, width, height);
        pl_tex result;
        assert(mp_motion_render(motion, a, b, 50 + pass * 2,
                                51 + pass * 2, 0.5f, &result));
        assert(result->params.w == width && result->params.h == height);
        struct mp_motion_stats stats = mp_motion_get_stats(motion);
        assert(stats.analysis_width == analysis_width);
        assert(stats.analysis_height == analysis_height);
        assert(stats.synthesis_width == width);
        assert(stats.synthesis_height == height);

        readback(gpu, result, output);
        double error = 0;
        int samples = 0;
        int margin = 16 * scale;
        for (int y = margin; y < height - margin; y++) {
            for (int x = margin; x < width - margin; x++) {
                float sx = (x + 0.5f) / scale - 0.5f;
                float sy = (y + 0.5f) / scale - 0.5f;
                for (int c = 0; c < 3; c++) {
                    int index = (y * width + x) * 4 + c;
                    error += fabsf(output[index] - affine_signal(sx, sy, c, 0.5f));
                    samples++;
                }
            }
        }
        error /= samples;
        printf("scaled analysis %dx%d -> synthesis %dx%d error=%.6f\n",
               analysis_width, analysis_height, width, height, error);
        fflush(stdout);
        assert(error < 0.025);

        mp_motion_reset(motion);
        pl_tex_destroy(gpu, &a);
        pl_tex_destroy(gpu, &b);
        free(first);
        free(second);
        free(output);
    }
}'''


def adapt(source: str) -> str:
    matches = list(FUNCTION.finditer(source))
    if len(matches) != 1:
        raise ValueError(f"expected one scaled_source_pair function, found {len(matches)}")
    old = matches[0].group(0)
    required_old = (
        "mp_motion_render_sources(",
        "mp_motion_render_sources_to(",
        "analysis_width = 128",
    )
    if not all(marker in old for marker in required_old):
        raise ValueError("app fixture no longer matches the expected source-pair test")
    adapted = source[: matches[0].start()] + REPLACEMENT + source[matches[0].end() :]
    if "mp_motion_render_sources(" in adapted or "mp_motion_render_sources_to(" in adapted:
        raise ValueError("removed source-pair API remains in the adapted fixture")
    for marker in (
        "stats.analysis_width == analysis_width",
        "stats.synthesis_width == width",
        "const int scales[] = {2, 4}",
    ):
        if marker not in adapted:
            raise ValueError(f"adapted fixture is missing scale coverage: {marker}")
    return adapted


def self_test() -> None:
    original = APP_FIXTURE.read_text(encoding="utf-8")
    adapted = adapt(original)
    if adapted == original:
        raise ValueError("adapter did not change the fixture")
    if "mp_motion_render_sources" in adapted:
        raise ValueError("adapter left an obsolete API reference")
    print("MPV 645 GPU motion fixture adapter: source-pair scaling coverage preserved")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mpv-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        self_test()
        if args.mpv_dir is None:
            return 0
    if args.mpv_dir is None:
        parser.error("--mpv-dir is required unless --self-test is used")

    mpv_dir = args.mpv_dir.resolve()
    actual_revision = subprocess.check_output(
        ["git", "-C", str(mpv_dir), "rev-parse", "HEAD"], text=True
    ).strip()
    if actual_revision != MPV_REVISION:
        raise SystemExit(
            f"expected pinned MPV {MPV_REVISION}, found {actual_revision}"
        )

    meson_test = (mpv_dir / "test/meson.build").read_text(encoding="utf-8")
    if meson_test.count("test('gpu-motion', gpu_motion, suite: 'gpu')") != 1:
        raise SystemExit("pinned MPV test/meson.build must contain its existing single gpu-motion target")

    destination = mpv_dir / "test/gpu_motion.c"
    destination.write_text(adapt(APP_FIXTURE.read_text(encoding="utf-8")), encoding="utf-8")
    print(f"Installed adapted app-owned fixture into temporary MPV checkout: {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
