#!/usr/bin/env python3
"""Add an opt-in, synchronous one-pixel Jelly probe to pinned MPV motion sources."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

JELLY_SHA256 = "a21153eec66e0181ecb9631dc51fce95194b60c85621605580ae91f880a41de0"
MARKER = "MPVFLOW_JELLY_PROBE_V1"
COUNT = 64
RECORD_BYTES = COUNT * 16


def _once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"motion probe anchor mismatch ({label}): expected 1, found {count}")
    return source.replace(old, new, 1)


def _glsl_decl(source: str, name: str) -> str:
    start = source.index(f"static const char {name}[] = GLSL(\n")
    end = source.index("\n);", start) + 3
    return source[start:end]


def _inject_probe_shader(source: str) -> str:
    """Port the validated scratch injector; it adds only side-channel writes."""
    start = """                          float blend, int hdr)
    {
        vec2 uv = (vec2(pixel) + 0.5) / output_extent;"""
    start_probe = """                          float blend, int hdr)
    {
        // MPVFLOW_JELLY_PROBE_V1: write-only side channel; never feeds color.
        bool probe_here = probe_enabled != 0 && all(equal(pixel, probe_pixel));
        if (probe_here) {
            for (int i = 0; i < 64; i++)
                probe_records[i] = vec4(0.0);
            probe_records[0] = vec4(1.0, 0.0, blend, 0.0);
        }
        vec2 uv = (vec2(pixel) + 0.5) / output_extent;"""
    source = _once(source, start, start_probe, "probe entry")

    scene = """        if (scene_change.r > 0.45 || scene_change.g > 0.08) {
            return blend < 0.5 ? original0 : original1;"""
    scene_probe = """        if (scene_change.r > 0.45 || scene_change.g > 0.08) {
            if (probe_here)
                probe_records[0] = vec4(1.0, 9.0, blend, scene_change.r);
            return blend < 0.5 ? original0 : original1;"""
    source = _once(source, scene, scene_probe, "scene-cut")

    center = """                float jelly_change = max(jelly_center_delta.r,
                                         max(jelly_center_delta.g, jelly_center_delta.b));
                if (jelly_change > 0.12 && blend > 0.000001 && blend < 0.999999) {"""
    center_probe = """                float jelly_change = max(jelly_center_delta.r,
                                         max(jelly_center_delta.g, jelly_center_delta.b));
                // reason codes: 1 center delta, 2 phase endpoint, 3 scan pending,
                // 4 no neighbor support, 5 fill threshold, 6 applied, 7 zero-flow,
                // 9 scene-cut; neighbor reason codes are recorded separately.
                int probe_reason = jelly_change <= 0.12 ? 1 :
                    ((blend <= 0.000001 || blend >= 0.999999) ? 2 : 3);
                if (probe_here) {
                    probe_records[0] = vec4(1.0, float(probe_reason), blend, jelly_change);
                    probe_records[1] = f;
                    probe_records[2] = r;
                    probe_records[3] = jelly_center0;
                    probe_records[4] = jelly_center1;
                    probe_records[5] = vec4(jelly_center_metric0, 1.0);
                    probe_records[6] = vec4(jelly_center_metric1, 1.0);
                    probe_records[7] = vec4(jelly_center_delta, jelly_change);
                }
                if (jelly_change > 0.12 && blend > 0.000001 && blend < 0.999999) {"""
    source = _once(source, center, center_probe, "center/Jelly gate")

    flow_reject = """                        if (!all(lessThanEqual(abs(neighbor_f), finite_limit)) ||
                            !all(lessThanEqual(abs(neighbor_r), finite_limit)) ||
                            min(neighbor_f.w, neighbor_r.w) <= 0.001)
                            continue;"""
    flow_probe = """                        int probe_neighbor_reason = 0;
                        int probe_neighbor_base = 9 + neighbor * 6;
                        if (probe_here) {
                            probe_records[probe_neighbor_base] = neighbor_f;
                            probe_records[probe_neighbor_base + 1] = neighbor_r;
                        }
                        if (!all(lessThanEqual(abs(neighbor_f), finite_limit)) ||
                            !all(lessThanEqual(abs(neighbor_r), finite_limit))) {
                            probe_neighbor_reason = 1;
                            if (probe_here)
                                probe_records[probe_neighbor_base + 5].z = float(probe_neighbor_reason);
                            continue;
                        }
                        if (min(neighbor_f.w, neighbor_r.w) <= 0.001) {
                            probe_neighbor_reason = 2;
                            if (probe_here)
                                probe_records[probe_neighbor_base + 5].z = float(probe_neighbor_reason);
                            continue;
                        }"""
    source = _once(source, flow_reject, flow_probe, "neighbor-flow rejection")

    color_reject = """                        if (any(isnan(jelly0.rgb)) || any(isinf(jelly0.rgb)) ||
                            any(isnan(jelly1.rgb)) || any(isinf(jelly1.rgb)))
                            continue;"""
    color_probe = """                        if (any(isnan(jelly0.rgb)) || any(isinf(jelly0.rgb)) ||
                            any(isnan(jelly1.rgb)) || any(isinf(jelly1.rgb))) {
                            probe_neighbor_reason = 3;
                            if (probe_here) {
                                probe_records[probe_neighbor_base + 2] = jelly0;
                                probe_records[probe_neighbor_base + 3] = jelly1;
                                probe_records[probe_neighbor_base + 5].z = float(probe_neighbor_reason);
                            }
                            continue;
                        }"""
    source = _once(source, color_reject, color_probe, "neighbor-color rejection")

    weights = """                        float jelly_w0 = 1.0 - smoothstep(0.16, 0.32, jelly_e0);
                        float jelly_w1 = 1.0 - smoothstep(0.16, 0.32, jelly_e1);
                        if (jelly_w0 > 0.5) {"""
    weights_probe = """                        float jelly_w0 = 1.0 - smoothstep(0.16, 0.32, jelly_e0);
                        float jelly_w1 = 1.0 - smoothstep(0.16, 0.32, jelly_e1);
                        if (probe_here) {
                            probe_records[probe_neighbor_base + 2] = vec4(jelly0.rgb, jelly_e0);
                            probe_records[probe_neighbor_base + 3] = vec4(jelly1.rgb, jelly_e1);
                            probe_records[probe_neighbor_base + 4] = vec4(jelly_w0, jelly_w1,
                                float(jelly_w0 > 0.5), float(jelly_w1 > 0.5));
                            probe_records[probe_neighbor_base + 5].z = 0.0;
                        }
                        if (jelly_w0 > 0.5) {"""
    source = _once(source, weights, weights_probe, "color-vote")

    summary = """                    bool jelly_support0 = jelly_count0 >= 5 && jelly_weight0 >= 3.0;
                    bool jelly_support1 = jelly_count1 >= 5 && jelly_weight1 >= 3.0;
                    if (jelly_support0 || jelly_support1) {"""
    summary_probe = """                    bool jelly_support0 = jelly_count0 >= 5 && jelly_weight0 >= 3.0;
                    bool jelly_support1 = jelly_count1 >= 5 && jelly_weight1 >= 3.0;
                    if (probe_here)
                        probe_records[8] = vec4(float(jelly_count0), float(jelly_count1),
                                                jelly_weight0, jelly_weight1);
                    if (jelly_support0 || jelly_support1) {"""
    source = _once(source, summary, summary_probe, "neighbor summary")

    fill = """                        float jelly_fill = clamp(0.65 * jelly_support * jelly_phase, 0.0, 0.65);
                        if (jelly_fill > 0.001)
                            return mix(original, jelly_region, jelly_fill);"""
    fill_probe = """                        float jelly_fill = clamp(0.65 * jelly_support * jelly_phase, 0.0, 0.65);
                        if (probe_here) {
                            probe_records[57] = vec4(float(jelly_support0), float(jelly_support1),
                                                     jelly_support, jelly_fill);
                            probe_records[0].y = jelly_fill > 0.001 ? 6.0 : 5.0;
                        }
                        if (jelly_fill > 0.001)
                            return mix(original, jelly_region, jelly_fill);"""
    source = _once(source, fill, fill_probe, "activation/fill")

    no_support = """                    }
                }
                if (all(equal(vec4(f.xy, r.xy), vec4(0.0)))) {"""
    no_support_probe = """                    }
                    if (probe_here && probe_records[0].y == 3.0)
                        probe_records[0].y = 4.0;
                }
                if (probe_here && probe_records[0].y == 4.0 &&
                    all(equal(vec4(f.xy, r.xy), vec4(0.0))))
                    probe_records[0].y = 7.0;
                if (all(equal(vec4(f.xy, r.xy), vec4(0.0)))) {"""
    source = _once(source, no_support, no_support_probe, "no-support/zero-flow")

    if source.count(MARKER) != 1:
        raise ValueError("probe marker postcondition failed")
    if "return mix(original, jelly_region, jelly_fill);" not in source:
        raise ValueError("the original Jelly color return was not preserved")
    return source


def transform_motion(source: str, *, verify_hash: bool = True) -> tuple[str, bool]:
    if MARKER in source:
        required = (
            "synthesis_probe_header",
            "synthesis_probe_body",
            "mp_motion_render_frames_probe",
            "mp_motion_render_views_probe",
        )
        if all(item in source for item in required):
            return source, False
        raise ValueError("motion probe marker/source mismatch")
    if verify_hash and hashlib.sha256(source.encode()).hexdigest() != JELLY_SHA256:
        raise ValueError("motion probe requires exact Jelly-transformed MPVRx motion.c")

    injected = _inject_probe_shader(source)
    probe_header = _glsl_decl(injected, "synthesis_pixel_header").replace(
        "synthesis_pixel_header", "synthesis_probe_header", 1
    )
    probe_body = _glsl_decl(source, "synthesis_body").replace(
        "synthesis_body", "synthesis_probe_body", 1
    )
    body_anchor = """    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    color = synthesize_pixel(first, second, forward, backward, scene, pixel,
                             output_size, motion_size, projection_ratio, alpha, linear_hdr);"""
    body_metadata = body_anchor + """
    // synthesize_pixel clears/writes the probe SSBO for this pixel. Preserve the
    // per-dispatch capture metadata by writing it only after that helper returns.
    if (probe_enabled != 0 && all(equal(pixel, probe_pixel))) {
        probe_records[58] = uintBitsToFloat(probe_id_halves);
        probe_records[59] = probe_metadata[0];
        probe_records[60] = probe_metadata[1];
        probe_records[61] = probe_metadata[2];
    }"""
    probe_body = _once(probe_body, body_anchor, body_metadata, "metadata write")

    out = source
    original_header = _glsl_decl(out, "synthesis_pixel_header")
    header_end = out.index(original_header) + len(original_header)
    out = out[:header_end] + "\n\n" + probe_header + out[header_end:]
    original_body = _glsl_decl(out, "synthesis_body")
    body_end = out.index(original_body) + len(original_body)
    out = out[:body_end] + "\n\n" + probe_body + out[body_end:]

    out = _once(
        out,
        "static bool synthesize(struct mp_motion *m, pl_tex first, pl_tex second,\n"
        "                       float alpha, pl_shader shader, int source_only)",
        "static bool synthesize(struct mp_motion *m, pl_tex first, pl_tex second,\n"
        "                       float alpha, pl_shader shader, int source_only,\n"
        "                       const struct mp_motion_probe_request *probe)",
        "synthesize signature",
    )
    desc_old = """    struct pl_shader_desc desc[] = {
        sampled_texels("first", first), sampled_texels("second", second),
        sampled("forward", m->warps[0]), sampled("backward", m->warps[1]),
        sampled("scene", m->scene),
    };"""
    desc_new = """    struct pl_shader_desc desc[6] = {
        sampled_texels("first", first), sampled_texels("second", second),
        sampled("forward", m->warps[0]), sampled("backward", m->warps[1]),
        sampled("scene", m->scene),
    };
    int probe_enabled = probe != NULL;
    int probe_pixel[2] = {probe ? probe->pixel_x : 0, probe ? probe->pixel_y : 0};
    /* Keep probe variables within Vulkan's minimum 128-byte push-constant limit. */
    float probe_metadata[12] = {0};
    uint32_t probe_id_halves[4] = {0};
    if (probe) {
        probe_id_halves[0] = (uint32_t) probe->first_id;
        probe_id_halves[1] = (uint32_t) (probe->first_id >> 32);
        probe_id_halves[2] = (uint32_t) probe->second_id;
        probe_id_halves[3] = (uint32_t) (probe->second_id >> 32);
        probe_metadata[0] = (float) probe->first_pts;
        probe_metadata[1] = (float) probe->second_pts;
        probe_metadata[2] = probe->alpha;
        probe_metadata[3] = (float) probe->selection_mode;
        probe_metadata[4] = (float) probe->pixel_x;
        probe_metadata[5] = (float) probe->pixel_y;
        probe_metadata[6] = (float) m->source_width;
        probe_metadata[7] = (float) m->source_height;
        probe_metadata[8] = (float) m->images[0][0]->params.w;
        probe_metadata[9] = (float) m->images[0][0]->params.h;
        probe_metadata[10] = m->linear_hdr ? 1.0f : 0.0f;
        probe_metadata[11] = source_only ? 1.0f : 0.0f;
    }
    struct pl_var records_var = pl_var_vec4("probe_records");
    records_var.dim_a = MP_MOTION_PROBE_RECORDS;
    struct pl_buffer_var records_buffer_var = {
        .var = records_var,
        .layout = pl_std430_layout(0, &records_var),
    };
    struct pl_shader_desc probe_desc = {
        .desc = {
            .name = "motion_probe",
            .type = PL_DESC_BUF_STORAGE,
            .access = PL_DESC_ACCESS_READWRITE,
        },
        .binding = {.object = probe ? probe->buffer : NULL},
        .buffer_vars = &records_buffer_var,
        .num_buffer_vars = 1,
        .memory = PL_MEMORY_VOLATILE,
    };
    if (probe)
        desc[5] = probe_desc;"""
    out = _once(out, desc_old, desc_new, "descriptors and metadata")

    vars_old = """    struct pl_shader_var vars[] = {
        {.var = pl_var_vec2("output_size"), .data = size},
        {.var = pl_var_vec2("motion_size"), .data = map_size},
        {.var = pl_var_float("alpha"), .data = &alpha, .dynamic = true},
        {.var = pl_var_int("linear_hdr"), .data = &hdr},
        {.var = pl_var_vec2("projection_ratio"), .data = projection, .dynamic = true},
        {.var = pl_var_int("source_only"), .data = &source_only, .dynamic = true},
    };"""
    vars_new = vars_old + """
    struct pl_var metadata_var = pl_var_vec4("probe_metadata");
    metadata_var.dim_a = 3;
    struct pl_shader_var probe_vars[] = {
        {.var = pl_var_vec2("output_size"), .data = size},
        {.var = pl_var_vec2("motion_size"), .data = map_size},
        {.var = pl_var_float("alpha"), .data = &alpha, .dynamic = true},
        {.var = pl_var_int("linear_hdr"), .data = &hdr},
        {.var = pl_var_vec2("projection_ratio"), .data = projection, .dynamic = true},
        {.var = pl_var_int("probe_enabled"), .data = &probe_enabled, .dynamic = true},
        {.var = pl_var_ivec2("probe_pixel"), .data = probe_pixel, .dynamic = true},
        {.var = metadata_var, .data = probe_metadata, .dynamic = true},
        {.var = pl_var_uvec4("probe_id_halves"), .data = probe_id_halves, .dynamic = true},
    };"""
    out = _once(out, vars_old, vars_new, "probe variables")

    out = _once(
        out,
        "static bool synthesize(struct mp_motion *m, pl_tex first, pl_tex second,\n"
        "                       float alpha, pl_shader shader, int source_only,\n"
        "                       const struct mp_motion_probe_request *probe)\n{\n",
        "static bool synthesize(struct mp_motion *m, pl_tex first, pl_tex second,\n"
        "                       float alpha, pl_shader shader, int source_only,\n"
        "                       const struct mp_motion_probe_request *probe)\n{\n"
        "    if (probe && (!probe->buffer || shader || source_only ||\n"
        "                  probe->pixel_x < 0 || probe->pixel_y < 0 ||\n"
        "                  probe->pixel_x >= m->source_width ||\n"
        "                  probe->pixel_y >= m->source_height))\n"
        "        return false;\n",
        "probe request validation",
    )
    out = _once(
        out,
        "        .header = synthesis_pixel_header,\n"
        "        .body = synthesis_body,\n"
        "        .descriptors = desc,\n"
        "        .num_descriptors = sizeof(desc) / sizeof(desc[0]),\n"
        "        .variables = vars,\n"
        "        // The compute path does not bind source_only.\n"
        "        .num_variables = sizeof(vars) / sizeof(vars[0]) - 1,\n",
        "        .header = probe ? synthesis_probe_header : synthesis_pixel_header,\n"
        "        .body = probe ? synthesis_probe_body : synthesis_body,\n"
        "        .descriptors = desc,\n"
        "        .num_descriptors = 5 + (probe != NULL),\n"
        "        .variables = probe ? probe_vars : vars,\n"
        "        // The compute path does not bind source_only.\n"
        "        .num_variables = probe ? sizeof(probe_vars) / sizeof(probe_vars[0])\n"
        "                                : sizeof(vars) / sizeof(vars[0]) - 1,\n",
        "conditional probe shader",
    )

    out = _once(
        out,
        "static bool render(struct mp_motion *m, pl_tex first, pl_tex second,\n"
        "                    uint64_t first_id, uint64_t second_id, float alpha,\n"
        "                    enum motion_input_kind input_kind, bool linear_hdr,\n"
        "                    pl_tex *output)",
        "static bool render(struct mp_motion *m, pl_tex first, pl_tex second,\n"
        "                    uint64_t first_id, uint64_t second_id, float alpha,\n"
        "                    enum motion_input_kind input_kind, bool linear_hdr,\n"
        "                    const struct mp_motion_probe_request *probe, pl_tex *output)",
        "render signature",
    )
    out = _once(
        out,
        "    if (output)\n        *output = NULL;\n    if (!m || !first || !second || !output || !isfinite(alpha) ||",
        "    if (output)\n        *output = NULL;\n    if (probe && (!probe->buffer || probe->pixel_x < 0 || probe->pixel_y < 0))\n"
        "        return false;\n    if (!m || !first || !second || !output || !isfinite(alpha) ||",
        "probe render validation",
    )
    out = _once(
        out,
        "!synthesize(m, first, second, alpha, NULL, 0) || pl_gpu_is_failed(m->gpu)",
        "!synthesize(m, first, second, alpha, NULL, 0, probe) || pl_gpu_is_failed(m->gpu)",
        "synthesis dispatch",
    )
    out = _once(
        out,
        "return render(m, first, second, first_id, second_id, alpha, MOTION_INPUT_RAW,\n"
        "                  false, output);",
        "return render(m, first, second, first_id, second_id, alpha, MOTION_INPUT_RAW,\n"
        "                  false, NULL, output);",
        "raw wrapper",
    )

    raw_api_anchor = "bool mp_motion_render_owned(struct mp_motion *m, pl_tex first, pl_tex second,"
    raw_api = """bool mp_motion_render_probe(struct mp_motion *m, pl_tex first, pl_tex second,
                           uint64_t first_id, uint64_t second_id, float alpha,
                           const struct mp_motion_probe_request *probe, pl_tex *output)
{
    if (!probe || !probe->buffer) {
        if (output)
            *output = NULL;
        return false;
    }
    return render(m, first, second, first_id, second_id, alpha, MOTION_INPUT_RAW,
                  false, probe, output);
}

"""
    out = _once(out, raw_api_anchor, raw_api + raw_api_anchor, "raw probe API")

    out = _once(
        out,
        "static enum mp_motion_result render_frames(\n"
        "    struct mp_motion *m, const struct mp_motion_frame *first,\n"
        "    const struct mp_motion_frame *second, float alpha, bool owned,\n"
        "    struct pl_frame *output)",
        "static enum mp_motion_result render_frames(\n"
        "    struct mp_motion *m, const struct mp_motion_frame *first,\n"
        "    const struct mp_motion_frame *second, float alpha, bool owned,\n"
        "    const struct mp_motion_probe_request *probe, struct pl_frame *output)",
        "frame helper signature",
    )
    out = _once(
        out,
        "alpha, MOTION_INPUT_CAPTURED, hdr, &texture)",
        "alpha, MOTION_INPUT_CAPTURED, hdr, probe, &texture)",
        "captured frame render",
    )
    out = _once(
        out,
        "return render_frames(m, first, second, alpha, false, output);",
        "return render_frames(m, first, second, alpha, false, NULL, output);",
        "frame wrapper",
    )
    out = _once(
        out,
        "return render_frames(m, first, second, alpha, true, output);",
        "return render_frames(m, first, second, alpha, true, NULL, output);",
        "owned frame wrapper",
    )
    frame_api_anchor = "enum mp_motion_result mp_motion_render_frames_owned(\n"
    frame_api = """enum mp_motion_result mp_motion_render_frames_probe(
    struct mp_motion *m, const struct mp_motion_frame *first,
    const struct mp_motion_frame *second, float alpha,
    const struct mp_motion_probe_request *probe, struct pl_frame *output)
{
    if (!probe || !probe->buffer) {
        if (output)
            *output = (struct pl_frame) {0};
        return MP_MOTION_ERROR;
    }
    return render_frames(m, first, second, alpha, false, probe, output);
}

"""
    out = _once(out, frame_api_anchor, frame_api + frame_api_anchor, "frame probe API")

    out = _once(
        out,
        "alpha, MOTION_INPUT_VIEW, true, output))",
        "alpha, MOTION_INPUT_VIEW, true, NULL, output))",
        "view render",
    )
    view_api_anchor = "bool mp_motion_shader_supported(const struct mp_motion *m)\n"
    view_api = """enum mp_motion_result mp_motion_render_views_probe(
    struct mp_motion *m, const struct pl_rendered_frame *first,
    const struct pl_rendered_frame *second, float alpha,
    const struct mp_motion_probe_request *probe, pl_tex *output)
{
    if (output)
        *output = NULL;
    enum mp_motion_result result = validate_views(m, first, second, alpha);
    if (result != MP_MOTION_OK)
        return result;
    if (!output)
        return MP_MOTION_UNSUPPORTED;
    if (!probe || !probe->buffer)
        return MP_MOTION_ERROR;
    if (!render(m, first->texture, second->texture, first->signature, second->signature,
                alpha, MOTION_INPUT_VIEW, true, probe, output))
        return MP_MOTION_ERROR;
    return MP_MOTION_OK;
}

"""
    out = _once(out, view_api_anchor, view_api + view_api_anchor, "view probe API")
    out = _once(
        out,
        "synthesize(m, view->texture, m->images[0][0], 0, shader, 1)",
        "synthesize(m, view->texture, m->images[0][0], 0, shader, 1, NULL)",
        "single view synthesis",
    )
    out = _once(
        out,
        "synthesize(m, first->texture, second->texture, alpha, shader, 0)",
        "synthesize(m, first->texture, second->texture, alpha, shader, 0, NULL)",
        "view synthesis",
    )
    return out, True


def transform_header(source: str) -> tuple[str, bool]:
    if MARKER in source:
        if (
            "#define MP_MOTION_PROBE_RECORDS 64" in source
            and "mp_motion_render_frames_probe" in source
            and "mp_motion_render_views_probe" in source
            and "mp_motion_render_probe" in source
        ):
            return source, False
        raise ValueError("motion probe header marker/source mismatch")
    request = """/* MPVFLOW_JELLY_PROBE_V1 */
#define MP_MOTION_PROBE_RECORDS 64
#define MP_MOTION_PROBE_RECORD_BYTES (MP_MOTION_PROBE_RECORDS * 16)
struct mp_motion_probe_request {
    pl_buf buffer;
    int pixel_x, pixel_y;
    uint64_t first_id, second_id;
    double first_pts, second_pts;
    float alpha;
    uint32_t selection_mode;
};
struct mp_motion;"""
    out = _once(source, "struct mp_motion;", request, "header request type")
    anchor = """enum mp_motion_result mp_motion_render_frames(
    struct mp_motion *motion, const struct mp_motion_frame *first,
    const struct mp_motion_frame *second, float alpha, struct pl_frame *output);"""
    extra = """
	enum mp_motion_result mp_motion_render_frames_probe(
	    struct mp_motion *motion, const struct mp_motion_frame *first,
	    const struct mp_motion_frame *second, float alpha,
	    const struct mp_motion_probe_request *probe, struct pl_frame *output);
	enum mp_motion_result mp_motion_render_views_probe(
	    struct mp_motion *motion, const struct pl_rendered_frame *first,
	    const struct pl_rendered_frame *second, float alpha,
	    const struct mp_motion_probe_request *probe, pl_tex *output);
	bool mp_motion_render_probe(
    struct mp_motion *motion, pl_tex first, pl_tex second,
    uint64_t first_id, uint64_t second_id, float alpha,
    const struct mp_motion_probe_request *probe, pl_tex *output);"""
    out = _once(out, anchor, anchor + extra, "header probe APIs")
    return out, True


def apply(motion: Path, header: Path) -> bool:
    motion_text, motion_changed = transform_motion(motion.read_text())
    header_text, header_changed = transform_header(header.read_text())
    if motion_changed:
        motion.write_text(motion_text)
    if header_changed:
        header.write_text(header_text)
    return motion_changed or header_changed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("motion_c", type=Path)
    parser.add_argument("motion_h", type=Path)
    args = parser.parse_args()
    apply(args.motion_c, args.motion_h)


if __name__ == "__main__":
    main()
