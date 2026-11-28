#!/usr/bin/env python3
"""Adapt the app-owned GPU motion fixture to MPV 645 for host Vulkan CI only.

The tracked app fixture predates MPV 645's removal of the explicit source-pair
helpers. This adapter changes only its source-pair API and capability-boundary
test in a temporary MPV checkout; no shipped runtime or tracked fixture source
is changed.
"""
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
APP_FIXTURE = ROOT / "app/src/main/cpp/mpvflow/pinned/test/gpu_motion.c"
APP_MOTION = ROOT / "app/src/main/cpp/mpvflow/pinned/motion.c"
APP_SHADER_GENERATOR = ROOT / "app/src/main/cpp/mpvflow/pinned/test/gpu_motion.py"
MPV_REVISION = "645f8ecdd3c94379048e9ee3e7014e174de0bd02"
MATCH_GROUP_SIZE_MARKER = "#define MATCH_GROUP_SIZE 64"
MATCH_SHARED_BYTES_MARKER = (
    "#define MATCH_SHARED_BYTES ((49 + 361) * 16 + "
    "(169 + 2 * MATCH_GROUP_SIZE) * 4)"
)
MPV645_SHARED_BYTES_EXPR = "((49 + 361) * 16 + (169 + 2 * 64) * 4)"
APP_SHARED_BYTES_EXPR = "49 * 16 + 169 * 4 - 1"
ADAPTED_SHARED_BYTES_EXPR = "MPV645_MATCH_SHARED_BYTES - 1"
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


APP_POLICY_FUNCTION = re.compile(
    r"^static void synthesis_policy\(pl_gpu gpu\)\n\{.*?^\}",
    re.MULTILINE | re.DOTALL,
)


def use_app_policy_shaders(source: str) -> str:
    matches = list(APP_POLICY_FUNCTION.finditer(source))
    if len(matches) != 1:
        raise ValueError(f"expected one synthesis_policy function, found {len(matches)}")
    start, end = matches[0].span()
    function = matches[0].group(0)
    for old, new in (
        (".header = test_sample_header,", ".header = app_test_sample_header,"),
        (".header = test_synthesis_header,", ".header = app_test_synthesis_header,"),
        (".body = test_synthesis_body,", ".body = app_test_synthesis_body,"),
    ):
        if function.count(old) != 1:
            raise ValueError("synthesis_policy no longer has exactly one app shader hook: " + old)
        function = function.replace(old, new, 1)
    return source[:start] + function + source[end:]


MPV_SAMPLING_FUNCTION = re.compile(
    r"^static void synthesis_sampling\(pl_gpu gpu\)\n\{.*?^\}",
    re.MULTILINE | re.DOTALL,
)


def adapt_mpv_synthesis_sampling(source: str) -> str:
    matches = list(MPV_SAMPLING_FUNCTION.finditer(source))
    if len(matches) != 1:
        raise ValueError(f"expected one synthesis_sampling function, found {len(matches)}")
    start, end = matches[0].span()
    function = matches[0].group(0)
    old_variables = """            float size[] = {width, height}, motion_size[] = {480, 270};
            float source_size[] = {width, height};
            struct pl_shader_var variables[] = {
                {.var = pl_var_vec2("motion_size"), .data = motion_size},
                {.var = pl_var_vec2("output_size"), .data = size},
                {.var = pl_var_vec2("source_size"), .data = source_size},
                {.var = pl_var_float("alpha"), .data = &alpha},
            };"""
    new_variables = """            float size[] = {width, height}, motion_size[] = {480, 270};
            int linear_hdr = 0;
            float projection_ratio[] = {
                (1.0f - alpha) / alpha, alpha / (1.0f - alpha),
            };
            struct pl_shader_var variables[] = {
                {.var = pl_var_vec2("motion_size"), .data = motion_size},
                {.var = pl_var_vec2("output_size"), .data = size},
                {.var = pl_var_float("alpha"), .data = &alpha},
                {.var = pl_var_int("linear_hdr"), .data = &linear_hdr},
                {.var = pl_var_vec2("projection_ratio"), .data = projection_ratio},
            };"""
    if function.count(old_variables) != 1:
        raise ValueError("synthesis_sampling no longer has the expected app-test uniform block")
    function = function.replace(old_variables, new_variables, 1)
    old_custom = """            assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
                .header = test_synthesis_header, .body = test_synthesis_body,
                .compute = true, .compute_group_size = {8, 8},
                .output = PL_SHADER_SIG_COLOR,
                .descriptors = descriptors, .num_descriptors = 5,
                .variables = variables, .num_variables = 4,
            }));"""
    new_custom = """            assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
                .header = test_synthesis_header,
            }));
            assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
                .header = test_synthesis_pixel_header,
            }));
            assert(pl_shader_custom(shader, &(struct pl_custom_shader) {
                .body = test_synthesis_body,
                .compute = true, .compute_group_size = {8, 8},
                .output = PL_SHADER_SIG_COLOR,
                .descriptors = descriptors, .num_descriptors = 5,
                .variables = variables, .num_variables = 5,
            }));"""
    if function.count(old_custom) != 1:
        raise ValueError("synthesis_sampling no longer has the expected MPV synthesis shader block")
    function = function.replace(old_custom, new_custom, 1)
    for marker in (
        '.header = test_synthesis_pixel_header,',
        '.body = test_synthesis_body,',
        'pl_var_int("linear_hdr")',
        'pl_var_vec2("projection_ratio")',
        '.num_variables = 5,',
    ):
        if marker not in function:
            raise ValueError("MPV 645 synthesis_sampling setup is missing " + marker)
    if "source_size" in function or "app_test_" in function:
        raise ValueError("pinned MPV 645 sampling regression was routed to app-only shader inputs")
    return source[:start] + function + source[end:]


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
    if source.count(APP_SHARED_BYTES_EXPR) != 1:
        raise ValueError("app fixture capability threshold no longer matches the expected test")
    adapted = source[: matches[0].start()] + REPLACEMENT + source[matches[0].end() :]
    adapted = use_app_policy_shaders(adapted)
    adapted = adapt_mpv_synthesis_sampling(adapted)
    include_marker = '#include "gpu_motion_shaders.h"\n'
    if adapted.count(include_marker) != 1:
        raise ValueError("expected one GPU motion shader include in the app fixture")
    adapted = adapted.replace(
        include_marker,
        include_marker
        + '#include "gpu_motion_app_shaders.h"\n'
        + f"\n#define MPV645_MATCH_SHARED_BYTES {MPV645_SHARED_BYTES_EXPR}\n",
        1,
    )
    adapted = adapted.replace(APP_SHARED_BYTES_EXPR, ADAPTED_SHARED_BYTES_EXPR, 1)
    if "mp_motion_render_sources(" in adapted or "mp_motion_render_sources_to(" in adapted:
        raise ValueError("removed source-pair API remains in the adapted fixture")
    for marker in (
        "stats.analysis_width == analysis_width",
        "stats.synthesis_width == width",
        "const int scales[] = {2, 4}",
        f"#define MPV645_MATCH_SHARED_BYTES {MPV645_SHARED_BYTES_EXPR}",
        ADAPTED_SHARED_BYTES_EXPR,
        '.header = app_test_sample_header,',
        '.header = app_test_synthesis_header,',
        '.body = app_test_synthesis_body,',
    ):
        if marker not in adapted:
            raise ValueError(f"adapted fixture is missing scale coverage: {marker}")
    return adapted


MPV_MOTION_TARGET = """motion_shaders = custom_target('motion-shaders',
        input: files('../video/out/gpu_next/motion.c', '../video/out/vo_gpu_next.c'),
        output: 'gpu_motion_shaders.h',
        command: [python, files('gpu_motion.py'), '--source', '@INPUT0@',
                  '--vo-source', '@INPUT1@',
                  '--generate', '@OUTPUT@'])"""
APP_MOTION_TARGET = """    app_motion_shaders = custom_target('app-motion-policy-shaders',
        input: files('mpvflow_app_motion.c'),
        depend_files: files('mpvflow_app_motion.py'),
        output: 'gpu_motion_app_shaders.h',
        command: [python, files('mpvflow_app_motion.py'), '--source', '@INPUT@',
                  '--generate', '@OUTPUT@', '--prefix', 'app_test_'])"""
MPV_MOTION_EXEC = "['gpu_motion.c', motion_shaders], link_with: libmpv_motion,"
BOTH_MOTION_EXECS = "['gpu_motion.c', motion_shaders, app_motion_shaders], link_with: libmpv_motion,"
GPU_TEST_TARGET = "test('gpu-motion', gpu_motion, suite: 'gpu')"
GPU_TEST_EXTENDED = "test('gpu-motion', gpu_motion, suite: 'gpu', timeout: 120)"


def adapt_meson(source: str) -> str:
    if source.count(MPV_MOTION_TARGET) != 1:
        raise ValueError("expected exactly one pinned MPV motion-shader custom target")
    if source.count(MPV_MOTION_EXEC) != 1:
        raise ValueError("expected exactly one pinned MPV gpu-motion executable source list")
    if source.count(GPU_TEST_TARGET) != 1:
        raise ValueError("expected exactly one gpu-motion test registration")
    adapted = source.replace(MPV_MOTION_TARGET, MPV_MOTION_TARGET + "\n" + APP_MOTION_TARGET, 1)
    adapted = adapted.replace(MPV_MOTION_EXEC, BOTH_MOTION_EXECS, 1)
    adapted = adapted.replace(GPU_TEST_TARGET, GPU_TEST_EXTENDED, 1)
    if "input: files('../video/out/gpu_next/motion.c', '../video/out/vo_gpu_next.c')" not in adapted:
        raise ValueError("the pinned MPV shader source was not preserved")
    if "input: files('mpvflow_app_motion.c')" not in adapted or "'--prefix', 'app_test_'" not in adapted:
        raise ValueError("app-only policy shader target is not separately prefixed")
    if "motion_shaders, app_motion_shaders" not in adapted:
        raise ValueError("gpu-motion executable is missing one of its distinct shader headers")
    if GPU_TEST_EXTENDED not in adapted:
        raise ValueError("the temporary gpu-motion test timeout was not extended")
    return adapted


def self_test() -> None:
    original = APP_FIXTURE.read_text(encoding="utf-8")
    adapted = adapt(original)
    if adapted == original:
        raise ValueError("adapter did not change the fixture")
    if "mp_motion_render_sources" in adapted:
        raise ValueError("adapter left an obsolete API reference")
    if "mp_motion_supported(&limited)" not in adapted:
        raise ValueError("adapter removed the capability-boundary regression")
    if 'app_test_warp_body' in adapted and '.body = app_test_warp_body,' in adapted:
        raise ValueError("an unrelated MPV warp regression was redirected to the app shader")
    if '.body = test_warp_body,' not in adapted:
        raise ValueError("pinned MPV 645 warp regression no longer uses its upstream shader")
    motion_source = APP_MOTION.read_text(encoding="utf-8")
    if "MPVFLOW_LOCAL_MOTION_SOFTENING_V1" not in motion_source:
        raise ValueError("app-owned helper lacks the local-softening policy under test")
    with tempfile.TemporaryDirectory(prefix="pr137-app-shaders-") as temporary:
        output = Path(temporary) / "gpu_motion_app_shaders.h"
        subprocess.check_call([
            sys.executable, str(APP_SHADER_GENERATOR), "--source", str(APP_MOTION),
            "--generate", str(output), "--prefix", "app_test_",
        ])
        generated = output.read_text(encoding="utf-8")
        if "MPVFLOW_LOCAL_MOTION_SOFTENING_V1" not in generated:
            raise ValueError("generated app shader header omits local softening")
        for symbol in ("app_test_sample_header", "app_test_synthesis_header", "app_test_synthesis_body"):
            if symbol not in generated:
                raise ValueError("generated app shader header is missing " + symbol)
    sample_meson = """motion_shaders = custom_target('motion-shaders',
        input: files('../video/out/gpu_next/motion.c', '../video/out/vo_gpu_next.c'),
        output: 'gpu_motion_shaders.h',
        command: [python, files('gpu_motion.py'), '--source', '@INPUT0@',
                  '--vo-source', '@INPUT1@',
                  '--generate', '@OUTPUT@'])
    gpu_motion = executable('gpu-motion',
        ['gpu_motion.c', motion_shaders], link_with: libmpv_motion,
        include_directories: incdir, dependencies: [libplacebo, libm])
    test('gpu-motion', gpu_motion, suite: 'gpu')"""
    if 'app_motion_shaders' not in adapt_meson(sample_meson):
        raise ValueError("Meson self-test failed to retain upstream shaders and add the app target")
    if GPU_TEST_EXTENDED not in adapt_meson(sample_meson):
        raise ValueError("Meson self-test did not extend only the gpu-motion test timeout")
    if "value > 0.10f && value < 0.14f" not in adapted:
        raise ValueError("adapter did not retain the app-specific positive midpoint assertion")
    print(
        "MPV 645 GPU motion fixture adapter: upstream warp tests preserved; "
        "MPV synthesis sampling interface preserved; app-owned policy isolated"
    )


def verify_pinned_capability_source(mpv_dir: Path) -> None:
    motion_source = (mpv_dir / "video/out/gpu_next/motion.c").read_text(encoding="utf-8")
    for marker in (MATCH_GROUP_SIZE_MARKER, MATCH_SHARED_BYTES_MARKER):
        if motion_source.count(marker) != 1:
            raise SystemExit(f"pinned MPV 645 motion capability source changed: missing {marker}")


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

    verify_pinned_capability_source(mpv_dir)

    meson_test = (mpv_dir / "test/meson.build").read_text(encoding="utf-8")
    if meson_test.count("test('gpu-motion', gpu_motion, suite: 'gpu')") != 1:
        raise SystemExit("pinned MPV test/meson.build must contain its existing single gpu-motion target")

    motion_source = APP_MOTION.read_text(encoding="utf-8")
    if "MPVFLOW_LOCAL_MOTION_SOFTENING_V1" not in motion_source:
        raise SystemExit("app-owned helper is missing the tested local-softening path")
    meson_path = mpv_dir / "test/meson.build"
    meson_source = meson_path.read_text(encoding="utf-8")
    meson_path.write_text(adapt_meson(meson_source), encoding="utf-8")

    destination = mpv_dir / "test/gpu_motion.c"
    destination.write_text(adapt(APP_FIXTURE.read_text(encoding="utf-8")), encoding="utf-8")
    app_motion = mpv_dir / "test/mpvflow_app_motion.c"
    app_motion.write_text(motion_source, encoding="utf-8")
    app_generator = mpv_dir / "test/mpvflow_app_motion.py"
    app_generator.write_text(APP_SHADER_GENERATOR.read_text(encoding="utf-8"), encoding="utf-8")
    print(f"Installed app-owned synthesis fixture alongside pinned MPV 645 tests: {destination}")
    print(f"Pinned MPV 645 shaders remain in gpu_motion_shaders.h; app synthesis shaders use gpu_motion_app_shaders.h from {app_motion}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
