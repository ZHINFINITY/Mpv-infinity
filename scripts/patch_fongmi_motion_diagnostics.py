#!/usr/bin/env python3
"""Add opt-in one-output diagnostic planes to pinned FongMi motion synthesis.

Run only after patch_fongmi_motion_artifact.py. The capture path is dormant
unless the VO explicitly arms it, and owns six bounded RGBA8 textures for one
sample only.
"""
from __future__ import annotations

import argparse
from pathlib import Path

SOURCE = Path("video/out/gpu_next/motion.c")
HEADER = Path("video/out/gpu_next/motion.h")
SOURCE_MARKER = "MPVFLOW_FONGMI_SINGLE_OUTPUT_CAPTURE_V2"
HEADER_MARKER = "MPVFLOW_FONGMI_DIAGNOSTIC_CAPTURE_API_V2"
PLANE_COUNT = 6


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"Pinned FongMi {label} anchor count is {count}; refusing an unverified patch")
    return source.replace(old, new, 1)


def transform_header(source: str) -> tuple[str, bool]:
    if "MPVFLOW_FONGMI_DIAGNOSTIC_CAPTURE_API_V1" in source:
        raise ValueError("Stale FongMi diagnostic header V1; rebuild from pinned source")
    if HEADER_MARKER in source:
        required = (
            "struct mp_motion_diagnostic_capture",
            "bool source_hdr;",
            "int capture_width, capture_height;",
            "mp_motion_source_is_hdr",
            "mp_motion_request_diagnostic_capture",
            "mp_motion_take_diagnostic_capture",
            "mp_motion_release_diagnostic_capture",
            "const char *failure_stage;",
            "int failed_plane;",
        )
        if source.count(HEADER_MARKER) != 1 or any(item not in source for item in required):
            raise ValueError("FongMi diagnostic header marker/source mismatch")
        return source, False
    anchor = "struct mp_motion_stats {\n"
    if source.count(anchor) != 1:
        raise ValueError("Pinned FongMi motion stats anchor changed; refusing an unverified header patch")
    source = source.replace(
        anchor,
        """/* MPVFLOW_FONGMI_DIAGNOSTIC_CAPTURE_API_V2 */
struct mp_motion_diagnostic_capture {
    // Borrowed textures in writer order: source0, source1, pre_confidence_warp,
    // fallback_color, trust_map, synthesized. All are RGBA8 UNORM.
    pl_tex planes[6];
    int width, height;
    int capture_width, capture_height;
    int analysis_width, analysis_height;
    int grid_width, grid_height;
    bool ready;
    bool hdr_rejected;
    bool source_hdr;
    bool srgb_encoded;
    const char *failure_stage;
    int failed_plane;
};

struct mp_motion_stats {
""",
        1,
    )
    anchor = "void mp_motion_reset(struct mp_motion *motion);\n"
    source = replace_once(
        source,
        anchor,
        anchor
        + """// Classify original source metadata; a rendered linear-light view is not itself HDR.
bool mp_motion_source_is_hdr(const struct pl_frame *source);
// Arm one output, or immediately expose a source-HDR refusal. The next eligible
// pair render writes six bounded RGBA8 planes for supported SDR sources.
// The caller must take and release the borrowed textures.
void mp_motion_request_diagnostic_capture(struct mp_motion *motion, bool source_hdr);
// Returns true once for a completed attempt, including a failed allocation/dispatch.
bool mp_motion_take_diagnostic_capture(
    struct mp_motion *motion, struct mp_motion_diagnostic_capture *capture);
void mp_motion_release_diagnostic_capture(struct mp_motion *motion);
""",
        "diagnostic API declarations",
    )
    return source, True


def transform_source(source: str) -> tuple[str, bool]:
    if "MPVFLOW_FONGMI_SINGLE_OUTPUT_CAPTURE_V1" in source:
        raise ValueError("Stale FongMi diagnostic source V1; rebuild from pinned source")
    if SOURCE_MARKER in source:
        required = (
            "MPVFLOW_FONGMI_SINGLE_OUTPUT_CAPTURE_V2",
            "mp_motion_capture_diagnostic_planes",
            "diagnostic_planes[6]",
            "diagnostic_source_hdr",
            "diagnostic_failure_stage",
            "diagnostic_failed_plane",
            "diagnostic_width, diagnostic_height",
            "capture_width = m->diagnostic_width",
            "source_extent",
            "pl_var_vec2(\"source_size\"), .data = source_size",
            "mp_motion_diagnostic_size",
            "source_position = uv * source_extent - 0.5",
            "sampling_position = diagnostic_mode == 0 ? vec2(pixel) : source_position",
            "sample_rgba(source0, sampling_position + f.xy * scale)",
            "plane_bytes * 7 > 64LL * 1024 * 1024",
            "source_dimensions_out_of_range",
            "texture_unavailable",
            "dispatch_failed",
            "mp_motion_source_is_hdr",
            "diagnostic_hdr_rejected = m->diagnostic_source_hdr",
            "diagnostic_mode == 3",
            "diagnostic_mode == 4",
            "diagnostic_mode == 5",
            "diagnostic_mode == 6",
            "PL_FMT_CAP_HOST_READABLE",
        )
        if source.count(SOURCE_MARKER) != 1 or any(item not in source for item in required):
            raise ValueError("FongMi diagnostic source marker/source mismatch")
        return source, False
    if source.count("MPVFLOW_ARTIFACT_GUARDS_V4") != 1:
        raise ValueError("FongMi V4 artifact patch is required before diagnostic capture")

    source = replace_once(
        source,
        """static bool hdr_source(const struct pl_frame *input)
{
    return input->color.transfer == PL_COLOR_TRC_PQ ||
           input->repr.sys == PL_COLOR_SYSTEM_DOLBYVISION;
}""",
        """static bool hdr_source(const struct pl_frame *input)
{
    return input->color.transfer == PL_COLOR_TRC_PQ ||
           input->repr.sys == PL_COLOR_SYSTEM_DOLBYVISION;
}

bool mp_motion_source_is_hdr(const struct pl_frame *source)
{
    return source &&
        (pl_color_transfer_is_hdr(source->color.transfer) ||
         source->repr.sys == PL_COLOR_SYSTEM_DOLBYVISION ||
         pl_color_space_is_hdr(&source->color));
}""",
        "diagnostic source HDR classifier",
    )

    # Store only owned staging outputs; normal render textures are untouched.
    source = replace_once(
        source,
        "    pl_tex result;\n    pl_dispatch_prepared pyramid_pass[LEVELS][2];",
        """    pl_tex result;
    // MPVFLOW_FONGMI_SINGLE_OUTPUT_CAPTURE_V2: allocated only for an armed one-shot.
    pl_tex diagnostic_planes[6];
    bool diagnostic_requested, diagnostic_attempted, diagnostic_ready;
    bool diagnostic_srgb_encoded, diagnostic_hdr_rejected, diagnostic_source_hdr;
    const char *diagnostic_failure_stage;
    int diagnostic_failed_plane;
    int diagnostic_width, diagnostic_height;
    pl_dispatch_prepared pyramid_pass[LEVELS][2];""",
        "diagnostic texture ownership",
    )

    # Diagnostic color planes are saved as display-readable sRGB PNG data for
    # the prepared-view route. Raw SDR inputs are already in their source domain.
    source = replace_once(
        source,
        "    vec4 synthesize_pixel(sampler2D source0, sampler2D source1, sampler2D warp0,",
        """    vec3 diagnostic_srgb(vec3 x)
    {
        x = max(x, vec3(0.0));
        vec3 low = 12.92 * x;
        vec3 high = 1.055 * pow(x, vec3(1.0 / 2.4)) - 0.055;
        return mix(high, low, lessThanEqual(x, vec3(0.0031308)));
    }

    vec4 diagnostic_color(vec4 value, int encode_srgb)
    {
        if (encode_srgb != 0)
            value.rgb = diagnostic_srgb(value.rgb);
        return value;
    }

    vec4 synthesize_pixel(sampler2D source0, sampler2D source1, sampler2D warp0,""",
        "diagnostic shader helpers",
    )
    source = replace_once(
        source,
        "vec2 output_extent, vec2 motion_extent, float blend, int hdr)",
        "vec2 output_extent, vec2 source_extent, vec2 motion_extent, float blend, int hdr,\n"
        "                          int diagnostic_mode, int encode_srgb)",
        "diagnostic shader parameters",
    )
    source = replace_once(
        source,
        """        vec2 uv = (vec2(pixel) + 0.5) / output_extent;
        ivec2 source_pixel = clamp(pixel, ivec2(0), textureSize(source0, 0) - 1);
        vec4 original0 = texelFetch(source0, source_pixel, 0);
        vec4 original1 = texelFetch(source1, source_pixel, 0);""",
        """        vec2 uv = (vec2(pixel) + 0.5) / output_extent;
        vec2 source_position = uv * source_extent - 0.5;
        vec2 sampling_position = diagnostic_mode == 0 ? vec2(pixel) : source_position;
        vec4 original0, original1;
        if (diagnostic_mode == 0) {
            ivec2 source_pixel = clamp(pixel, ivec2(0), textureSize(source0, 0) - 1);
            original0 = texelFetch(source0, source_pixel, 0);
            original1 = texelFetch(source1, source_pixel, 0);
        } else {
            original0 = sample_rgba(source0, source_position);
            original1 = sample_rgba(source1, source_position);
        }""",
        "normalized diagnostic source sampling",
    )
    source = replace_once(
        source,
        "vec2 scale = output_extent / motion_extent;",
        "vec2 scale = source_extent / motion_extent;",
        "source-pixel motion vector scaling",
    )
    for old, new, label in (
        ("sample_rgba(source0, vec2(pixel) + f.xy * scale)",
         "sample_rgba(source0, sampling_position + f.xy * scale)", "forward warp source coordinates"),
        ("sample_rgba(source1, vec2(pixel) + r.xy * scale)",
         "sample_rgba(source1, sampling_position + r.xy * scale)", "reverse warp source coordinates"),
        ("sample_rgba(source0, vec2(pixel) + f.xy * scale + local_motion_offset)",
         "sample_rgba(source0, sampling_position + f.xy * scale + local_motion_offset)", "forward soft tap coordinates"),
        ("sample_rgba(source1, vec2(pixel) + r.xy * scale - local_motion_offset)",
         "sample_rgba(source1, sampling_position + r.xy * scale - local_motion_offset)", "reverse soft tap coordinates"),
    ):
        source = replace_once(source, old, new, label)
    source = replace_once(
        source,
        """        vec2 scene_change = texelFetch(scene_map, ivec2(0), 0).rg;
        if (scene_change.r > 0.45 || scene_change.g > 0.08) {
            return blend < 0.5 ? original0 : original1;
        } else {""",
        """        if (diagnostic_mode == 1)
            return diagnostic_color(original0, encode_srgb);
        if (diagnostic_mode == 2)
            return diagnostic_color(original1, encode_srgb);
        vec2 scene_change = texelFetch(scene_map, ivec2(0), 0).rg;
        bool scene_cut = scene_change.r > 0.45 || scene_change.g > 0.08;
        if (scene_cut && diagnostic_mode == 0)
            return blend < 0.5 ? original0 : original1;
        if (scene_cut) {
            if (diagnostic_mode == 3) return vec4(0.0, 0.0, 0.0, 1.0);
            if (diagnostic_mode == 4)
                return diagnostic_color(mix(original0, original1, blend), encode_srgb);
            if (diagnostic_mode == 5) return vec4(0.0, 0.0, 0.0, 0.0);
            vec4 endpoint = blend < 0.5 ? original0 : original1;
            return diagnostic_mode == 6 ? diagnostic_color(endpoint, encode_srgb) : endpoint;
        } else {""",
        "scene-cut diagnostic branch",
    )
    source = replace_once(
        source,
        """            return mix(source_blend, mix(warped0, warped1, blend), clamp(trust, 0.0, 1.0));""",
        """            vec4 raw_warped = mix(warped0, warped1, blend);
            if (diagnostic_mode == 3)
                return diagnostic_color(raw_warped, encode_srgb);
            if (diagnostic_mode == 4)
                return diagnostic_color(source_blend, encode_srgb);
            float applied_trust = clamp(trust, 0.0, 1.0);
            if (diagnostic_mode == 5)
                return vec4(applied_trust, applied_trust, applied_trust, 1.0);
            vec4 final_output = mix(source_blend, raw_warped, applied_trust);
            return diagnostic_mode == 6
                ? diagnostic_color(final_output, encode_srgb) : final_output;""",
        "intermediate diagnostic outputs",
    )
    shader_call = "output_size, motion_size, alpha, linear_hdr);"
    if source.count(shader_call) != 2:
        raise ValueError("Pinned FongMi synthesis wrapper call count changed; refusing an unverified patch")
    source = source.replace(
        shader_call,
        "output_size, source_size, motion_size, alpha, linear_hdr, diagnostic_mode, encode_srgb);",
        2,
    )

    # Add a sampleable, host-readable RGBA8 target allocator. The hard caps match
    # the native one-pair writer (six RGBA8 planes plus one readback, <= 64 MiB).
    source = replace_once(
        source,
        "static bool recreate(struct mp_motion *m, pl_tex *texture, int w, int h)\n{",
        """static bool recreate_diagnostic(struct mp_motion *m, pl_tex *texture, int w, int h)
{
    const unsigned int caps = PL_FMT_CAP_SAMPLEABLE | PL_FMT_CAP_STORABLE |
                              PL_FMT_CAP_HOST_READABLE;
    pl_fmt format = pl_find_fmt(m->gpu, PL_FMT_UNORM, 4, 8, 8, caps);
    return format && pl_tex_recreate(m->gpu, texture, pl_tex_params(
        .w = w, .h = h, .format = format, .sampleable = true,
        .storable = true, .host_readable = true,
    ));
}

static bool recreate(struct mp_motion *m, pl_tex *texture, int w, int h)
{""",
        "host-readable RGBA8 texture allocator",
    )

    source = replace_once(
        source,
        """static bool synthesize(struct mp_motion *m, pl_tex first, pl_tex second,
                       float alpha, pl_shader shader, int source_only)
{""",
        """static bool synthesize(struct mp_motion *m, pl_tex first, pl_tex second,
                       float alpha, pl_shader shader, int source_only,
                       int diagnostic_mode, int encode_srgb, pl_tex target)
{""",
        "synthesis diagnostic parameters",
    )
    source = replace_once(
        source,
        """static bool synthesize(struct mp_motion *m, pl_tex first, pl_tex second,
                       float alpha, pl_shader shader, int source_only,
                       int diagnostic_mode, int encode_srgb, pl_tex target)
{
    float size[] = {m->source_width, m->source_height};
    float map_size[] = {m->images[0][0]->params.w, m->images[0][0]->params.h};""",
        """static bool synthesize(struct mp_motion *m, pl_tex first, pl_tex second,
                       float alpha, pl_shader shader, int source_only,
                       int diagnostic_mode, int encode_srgb, pl_tex target)
{
    float output_size[] = {
        target ? target->params.w : m->source_width,
        target ? target->params.h : m->source_height,
    };
    float source_size[] = {m->source_width, m->source_height};
    float map_size[] = {m->images[0][0]->params.w, m->images[0][0]->params.h};""",
        "diagnostic output and source extents",
    )
    source = replace_once(
        source,
        """        {.var = pl_var_int("linear_hdr"), .data = &hdr},
        {.var = pl_var_int("source_only"), .data = &source_only, .dynamic = true},""",
        """        {.var = pl_var_int("linear_hdr"), .data = &hdr},
        {.var = pl_var_int("diagnostic_mode"), .data = &diagnostic_mode, .dynamic = true},
        {.var = pl_var_int("encode_srgb"), .data = &encode_srgb},
        {.var = pl_var_int("source_only"), .data = &source_only, .dynamic = true},""",
        "synthesis diagnostic uniforms",
    )
    source = replace_once(
        source,
        "        {.var = pl_var_vec2(\"output_size\"), .data = size},\n        {.var = pl_var_vec2(\"motion_size\"), .data = map_size},",
        "        {.var = pl_var_vec2(\"output_size\"), .data = output_size},\n"
        "        {.var = pl_var_vec2(\"source_size\"), .data = source_size},\n"
        "        {.var = pl_var_vec2(\"motion_size\"), .data = map_size},",
        "source-space synthesis uniform",
    )
    source = replace_once(source, ".variables = vars, .num_variables = 4,", ".variables = vars, .num_variables = 7,", "compute shader variable count")
    source = replace_once(source, "custom.num_variables = 5;", "custom.num_variables = 8;", "fused shader variable count")
    source = replace_once(
        source,
        "return dispatch(m, m->result, &custom);",
        "return dispatch(m, target ? target : m->result, &custom);",
        "synthesis target selection",
    )

    # Append one capture pass per channel only after the real motion and trust
    # fields have been computed. Failure marks this diagnostic attempt failed,
    # but never fails or changes normal playback.
    source = replace_once(
        source,
        """static void record_synthesis(struct mp_motion *m)
{""",
        """static bool mp_motion_diagnostic_size(int width, int height,
                                         int *capture_width, int *capture_height)
{
    if (width < 8 || height < 8 || width > 4096 || height > 4096 ||
        (int64_t) width * height > 16777216 || !capture_width || !capture_height)
        return false;
    const int largest = width > height ? width : height;
    int64_t scaled_width = width, scaled_height = height;
    if (largest > 1920) {
        const int64_t half = largest / 2;
        scaled_width = ((int64_t) width * 1920 + half) / largest;
        scaled_height = ((int64_t) height * 1920 + half) / largest;
    }
    if (scaled_width < 1) scaled_width = 1;
    if (scaled_height < 1) scaled_height = 1;
    while (scaled_width * scaled_height > 2073600) {
        if (scaled_width * height >= scaled_height * width)
            scaled_width--;
        else
            scaled_height--;
    }
    const int64_t plane_bytes = scaled_width * scaled_height * 4;
    if (scaled_width < 8 || scaled_height < 8 || scaled_width > 1920 ||
        scaled_height > 1920 || plane_bytes * 7 > 64LL * 1024 * 1024)
        return false;
    *capture_width = (int) scaled_width;
    *capture_height = (int) scaled_height;
    return true;
}

static bool mp_motion_capture_diagnostic_planes(
    struct mp_motion *m, pl_tex first, pl_tex second, float alpha)
{
    if (!m->diagnostic_requested)
        return true;
    m->diagnostic_requested = false;
    m->diagnostic_attempted = true;
    m->diagnostic_ready = false;
    m->diagnostic_hdr_rejected = m->diagnostic_source_hdr;
    m->diagnostic_failure_stage = NULL;
    m->diagnostic_failed_plane = -1;
    if (m->diagnostic_hdr_rejected) {
        m->diagnostic_failure_stage = "actual_hdr_source_unsupported";
        return false;
    }
    int w = first->params.w, h = first->params.h;
    int capture_width = 0, capture_height = 0;
    if (!mp_motion_diagnostic_size(w, h, &capture_width, &capture_height)) {
        m->diagnostic_failure_stage = "source_dimensions_out_of_range";
        return false;
    }
    m->diagnostic_width = capture_width;
    m->diagnostic_height = capture_height;
    for (int i = 0; i < 6; i++) {
        if (!recreate_diagnostic(m, &m->diagnostic_planes[i], capture_width, capture_height)) {
            m->diagnostic_failure_stage = "texture_unavailable";
            m->diagnostic_failed_plane = i;
            return false;
        }
    }
    int encode_srgb = m->input_kind == MOTION_INPUT_VIEW;
    for (int mode = 1; mode <= 6; mode++) {
        if (!synthesize(m, first, second, alpha, NULL, 0, mode, encode_srgb,
                        m->diagnostic_planes[mode - 1])) {
            m->diagnostic_failure_stage = "dispatch_failed";
            m->diagnostic_failed_plane = mode - 1;
            return false;
        }
    }
    m->diagnostic_srgb_encoded = encode_srgb != 0;
    m->diagnostic_ready = true;
    m->diagnostic_failure_stage = NULL;
    m->diagnostic_failed_plane = -1;
    return true;
}

void mp_motion_request_diagnostic_capture(struct mp_motion *m, bool source_hdr)
{
    if (!m)
        return;
    m->diagnostic_requested = !source_hdr;
    m->diagnostic_attempted = source_hdr;
    m->diagnostic_ready = false;
    m->diagnostic_width = 0;
    m->diagnostic_height = 0;
    m->diagnostic_hdr_rejected = source_hdr;
    m->diagnostic_source_hdr = source_hdr;
    m->diagnostic_failure_stage = source_hdr ? "actual_hdr_source_unsupported" : NULL;
    m->diagnostic_failed_plane = -1;
}

bool mp_motion_take_diagnostic_capture(
    struct mp_motion *m, struct mp_motion_diagnostic_capture *capture)
{
    if (!m || !capture || !m->diagnostic_attempted)
        return false;
    *capture = (struct mp_motion_diagnostic_capture) {
        .width = m->source_width,
        .height = m->source_height,
        .capture_width = m->diagnostic_width,
        .capture_height = m->diagnostic_height,
        .analysis_width = m->stats.analysis_width,
        .analysis_height = m->stats.analysis_height,
        .grid_width = m->vectors[0][0] ? m->vectors[0][0]->params.w : 0,
        .grid_height = m->vectors[0][0] ? m->vectors[0][0]->params.h : 0,
        .ready = m->diagnostic_ready,
        .hdr_rejected = m->diagnostic_hdr_rejected,
        .source_hdr = m->diagnostic_source_hdr,
        .srgb_encoded = m->diagnostic_srgb_encoded,
        .failure_stage = m->diagnostic_failure_stage,
        .failed_plane = m->diagnostic_failed_plane,
    };
    for (int i = 0; i < 6; i++)
        capture->planes[i] = m->diagnostic_planes[i];
    m->diagnostic_attempted = false;
    m->diagnostic_ready = false;
    return true;
}

void mp_motion_release_diagnostic_capture(struct mp_motion *m)
{
    if (!m)
        return;
    for (int i = 0; i < 6; i++)
        pl_tex_destroy(m->gpu, &m->diagnostic_planes[i]);
    m->diagnostic_requested = false;
    m->diagnostic_ready = false;
    m->diagnostic_attempted = false;
    m->diagnostic_width = 0;
    m->diagnostic_height = 0;
    m->diagnostic_hdr_rejected = false;
    m->diagnostic_source_hdr = false;
    m->diagnostic_srgb_encoded = false;
    m->diagnostic_failure_stage = NULL;
    m->diagnostic_failed_plane = -1;
}

static void record_synthesis(struct mp_motion *m)
{""",
        "bounded diagnostic capture API implementation",
    )

    # Normal output uses diagnostic_mode=0 and exactly the pre-existing target.
    source = replace_once(
        source,
        """        m->stats.repeated_frames++;
        *output = m->result;""",
        """        if (m->diagnostic_requested)
            mp_motion_capture_diagnostic_planes(m, first, second, alpha);
        m->stats.repeated_frames++;
        *output = m->result;""",
        "cached-output diagnostic capture",
    )
    source = replace_once(
        source,
        """    if (!warp_passes(m, alpha, false) || !synthesize(m, first, second, alpha, NULL, 0))
        return false;""",
        """    if (!warp_passes(m, alpha, false) ||
        !synthesize(m, first, second, alpha, NULL, 0, 0, 0, NULL))
        return false;
    if (m->diagnostic_requested)
        mp_motion_capture_diagnostic_planes(m, first, second, alpha);""",
        "normal-output diagnostic capture",
    )
    source = replace_once(
        source,
        """    return synthesize(m, view->texture, m->images[0][0], 0, shader, 1)
        ? MP_MOTION_OK : MP_MOTION_ERROR;""",
        """    return synthesize(m, view->texture, m->images[0][0], 0, shader, 1,
                      0, 0, NULL) ? MP_MOTION_OK : MP_MOTION_ERROR;""",
        "single-view shader API call",
    )
    source = replace_once(
        source,
        """    if (!prepare_resources(m, first->texture, second->texture) ||
        !analyze(m, first->texture, second->texture, first->signature,
                 second->signature) || !warp_passes(m, alpha, false) ||
        !synthesize(m, first->texture, second->texture, alpha, shader, 0))
        return MP_MOTION_ERROR;""",
        """    if (!prepare_resources(m, first->texture, second->texture) ||
        !analyze(m, first->texture, second->texture, first->signature,
                 second->signature) || !warp_passes(m, alpha, false))
        return MP_MOTION_ERROR;
    if (m->diagnostic_requested)
        mp_motion_capture_diagnostic_planes(m, first->texture, second->texture, alpha);
    if (!synthesize(m, first->texture, second->texture, alpha, shader, 0,
                    0, 0, NULL))
        return MP_MOTION_ERROR;""",
        "fused-view shader diagnostic capture",
    )
    source = replace_once(
        source,
        """    pl_tex_destroy(m->gpu, &m->result);
    destroy_passes(m);""",
        """    pl_tex_destroy(m->gpu, &m->result);
    mp_motion_release_diagnostic_capture(m);
    destroy_passes(m);""",
        "diagnostic texture destruction",
    )
    if source.count(SOURCE_MARKER) != 1 or "diagnostic_mode == 5" not in source:
        raise ValueError("FongMi diagnostic source postconditions failed")
    return source, True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mpv_dir", type=Path)
    args = parser.parse_args()
    source_path = args.mpv_dir / SOURCE
    header_path = args.mpv_dir / HEADER
    if not source_path.is_file() or not header_path.is_file():
        raise SystemExit("Pinned FongMi motion source/header is missing")
    try:
        source, source_changed = transform_source(source_path.read_text(encoding="utf-8"))
        header, header_changed = transform_header(header_path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if source_changed:
        source_path.write_text(source, encoding="utf-8")
    if header_changed:
        header_path.write_text(header, encoding="utf-8")
    print("FongMi one-shot synthesis diagnostic planes:",
          "patched" if source_changed or header_changed else "already patched")


if __name__ == "__main__":
    main()
