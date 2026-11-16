#!/usr/bin/env python3
"""Opt-in patch for the pinned FongMi native Jelly synthesis probe."""
from __future__ import annotations
import argparse
import hashlib
import shutil
from pathlib import Path

VO = Path("video/out/vo_gpu_next.c")
MESON = Path("meson.build")
MARKER = "MPVFLOW_FONGMI_VO_JELLY_PROBE_V1"


def replace_once(text: str, old: str, new: str, name: str) -> str:
    n = text.count(old)
    if n != 1:
        raise ValueError(f"{name}: expected one pinned anchor, found {n}")
    return text.replace(old, new, 1)


def apply_patch(vo_source_path, meson_path, repo_writer_dir, probe_enabled):
    """Patch the fetched MPV tree only when explicitly opted in."""
    vo_path, meson_file = Path(vo_source_path), Path(meson_path)
    if not probe_enabled:
        return False
    source, meson = vo_path.read_text(), meson_file.read_text()
    if MARKER in source or MARKER in meson:
        if MARKER not in source or MARKER not in meson:
            raise ValueError("partial Jelly probe patch detected")
        return False
    writer_dir = Path(repo_writer_dir)
    header, implementation = (writer_dir / "mpvrx_jelly_probe_writer.h",
                              writer_dir / "mpvrx_jelly_probe_writer.c")
    if not header.is_file() or not implementation.is_file():
        raise FileNotFoundError("writer C/H must be present in repo_writer_dir")

    include_anchor = '#include "gpu/utils.h"\n'
    source = replace_once(source, include_anchor, include_anchor +
        '#include <errno.h>\n#include <math.h>\n#include <string.h>\n'
        '#include "mpvrx_jelly_probe_writer.h"\n', "include")
    forward_anchor = 'struct osd_entry {\n'
    source = replace_once(source, forward_anchor,
        'struct priv;\nstatic void jelly_probe_poll(struct priv *p);\n'
        'static void jelly_probe_finish(struct priv *p);\n\n' + forward_anchor,
        "probe helper declarations")
    struct_anchor = '    struct mp_motion_clock motion_clock;\n'
    struct_fields = '''    struct mp_motion_clock motion_clock;
    /* MPVFLOW_FONGMI_VO_JELLY_PROBE_V1 */
    bool jelly_probe_armed, jelly_probe_pending, jelly_probe_writer_ready;
    bool jelly_probe_last_record_valid, jelly_probe_setup_attempted;
    bool jelly_probe_finalized;
    pl_buf jelly_probe_buf;
    struct mp_motion *jelly_probe_motion;
    struct mpvrx_jelly_probe_writer jelly_probe_writer;
    struct mpvrx_jelly_probe_diagnostics jelly_probe_diagnostics;
'''
    source = replace_once(source, struct_anchor, struct_fields, "priv fields")
    options_anchor = '    int motion_fps;\n    struct user_lut lut;\n'
    source = replace_once(source, options_anchor, options_anchor +
        '    char *fongmi_jelly_probe_report_file;\n    bool fongmi_jelly_probe_arm;\n', "option fields")
    table_anchor = '        {"motion-interpolation", OPT_INT(motion_fps), M_RANGE(0, 240)},\n'
    source = replace_once(source, table_anchor, table_anchor +
        '        {"fongmi-jelly-probe-report-file", OPT_STRING(fongmi_jelly_probe_report_file)},\n'
        '        {"fongmi-jelly-probe-arm", OPT_BOOL(fongmi_jelly_probe_arm)},\n', "option table")

    # Keep all file and worker setup in the control/update path, never in rendering.
    update_anchor = '    update_motion_options(vo);\n    publish_motion_info(vo);\n'
    setup = '''    update_motion_options(vo);
    publish_motion_info(vo);
    /* Probe files, worker and GPU state are created only on this control path. */
    if (p->next_opts->fongmi_jelly_probe_arm &&
        p->next_opts->fongmi_jelly_probe_report_file &&
        p->next_opts->fongmi_jelly_probe_report_file[0] && !p->jelly_probe_setup_attempted) {
        static const unsigned char zero_record[MPVRX_JELLY_PROBE_RECORD_BYTES] = {0};
        struct pl_buf_params bp = {
            .size = MPVRX_JELLY_PROBE_RECORD_BYTES, .storable = true,
            .host_mapped = true, .host_readable = true,
            .memory_type = PL_BUF_MEM_DEVICE,
            .initial_data = zero_record,
        };
        struct mpvrx_jelly_probe_diagnostics *d = &p->jelly_probe_diagnostics;
        p->jelly_probe_setup_attempted = true;
        memset(d, 0, sizeof(*d));
        d->setup_status = MPVRX_JELLY_STAGE_NOT_REACHED;
        d->setup_error = d->writer_init_error = d->buffer_error = -1;
        d->writer_init_status = MPVRX_JELLY_STAGE_NOT_REACHED;
        d->buffer_status = MPVRX_JELLY_STAGE_NOT_REACHED;
        d->pixel_x = d->pixel_y = -1;
        d->source_width = d->source_height = d->pixel_in_bounds = -1;
        d->motion_views_enabled = -1;
        d->last_candidate_view_width = d->last_candidate_view_height = -1;
        d->last_pts_valid = d->last_selection_mode = -1;
        d->gpu_last_poll_status = MPVRX_JELLY_READBACK_NOT_SUBMITTED;
        d->pixel_evaluated = d->jelly_reason_code = d->activation = -1;
        d->jelly_fill = NAN;
        int writer_error = mpvrx_jelly_probe_writer_init(&p->jelly_probe_writer,
            p->next_opts->fongmi_jelly_probe_report_file);
        if (writer_error) {
            d->writer_init_status = MPVRX_JELLY_STAGE_FAILED;
            d->writer_init_error = writer_error;
            d->setup_status = MPVRX_JELLY_STAGE_FAILED;
            d->setup_error = writer_error;
        } else {
            p->jelly_probe_writer_ready = true;
            d->writer_init_status = MPVRX_JELLY_STAGE_OK;
            d->writer_init_error = 0;
            int buffer_error = 0;
            if (!p->gpu) {
                buffer_error = ENODEV;
            } else if (p->gpu->limits.max_buf_size < MPVRX_JELLY_PROBE_RECORD_BYTES ||
                       p->gpu->limits.max_ssbo_size < MPVRX_JELLY_PROBE_RECORD_BYTES ||
                       p->gpu->limits.max_mapped_size < MPVRX_JELLY_PROBE_RECORD_BYTES ||
                       p->gpu->limits.max_mapped_vram < MPVRX_JELLY_PROBE_RECORD_BYTES) {
                buffer_error = ENOTSUP;
            } else {
                p->jelly_probe_buf = pl_buf_create(p->gpu, &bp);
                if (!p->jelly_probe_buf)
                    buffer_error = ENOMEM;
                else if (!p->jelly_probe_buf->data)
                    buffer_error = EFAULT;
            }
            if (buffer_error) {
                d->buffer_status = MPVRX_JELLY_STAGE_FAILED;
                d->buffer_error = buffer_error;
                d->setup_status = MPVRX_JELLY_STAGE_FAILED;
                d->setup_error = buffer_error;
                if (p->jelly_probe_buf)
                    pl_buf_destroy(p->gpu, &p->jelly_probe_buf);
            } else {
                d->buffer_status = MPVRX_JELLY_STAGE_OK;
                d->buffer_error = 0;
                p->jelly_probe_motion = mp_motion_create(p->gpu, 480);
                enum mpvrx_jelly_probe_capture_result arm_result =
                    mpvrx_jelly_probe_writer_arm(&p->jelly_probe_writer);
                if (arm_result == MPVRX_JELLY_CAPTURE_ACCEPTED) {
                    p->jelly_probe_armed = p->jelly_probe_motion != NULL;
                    d->setup_status = p->jelly_probe_motion
                        ? MPVRX_JELLY_STAGE_OK : MPVRX_JELLY_STAGE_FAILED;
                    d->setup_error = p->jelly_probe_motion ? 0 : ENOMEM;
                } else {
                    d->setup_status = MPVRX_JELLY_STAGE_FAILED;
                    d->setup_error = (int)arm_result;
                }
            }
        }
    }
    /* Clearing the one-shot arm option finalizes the last eligible skip. */
    if (!p->next_opts->fongmi_jelly_probe_arm && p->jelly_probe_writer_ready &&
        !p->jelly_probe_finalized) {
        if (p->gpu && p->jelly_probe_buf)
            pl_gpu_finish(p->gpu);
        jelly_probe_poll(p);
        if (p->jelly_probe_armed && p->jelly_probe_last_record_valid &&
            p->jelly_probe_buf &&
            mpvrx_jelly_probe_writer_capture(&p->jelly_probe_writer,
                                              p->jelly_probe_buf->data) == MPVRX_JELLY_CAPTURE_ACCEPTED)
            p->jelly_probe_armed = false;
        p->jelly_probe_armed = false;
        jelly_probe_finish(p);
        mp_motion_destroy(&p->jelly_probe_motion);
    }
'''
    source = replace_once(source, update_anchor, setup, "control update")

    # Callback-only poll: never wait, allocate, write files, or serialize JSON here.
    frame_anchor = 'static int prepare_motion_frame(struct vo *vo, const struct pl_frame_mix *mix,\n'
    poll = '''static bool jelly_probe_pixel_for_size(int width, int height,
                                       int *pixel_x, int *pixel_y)
{
    if (width <= 0 || height <= 0 || !pixel_x || !pixel_y)
        return false;
    /* Preserve the clip's tested point as a fraction of a 1920x1080 source. */
    *pixel_x = (int) ((double) width * 1352.0 / 1920.0);
    *pixel_y = (int) ((double) height * 224.0 / 1080.0);
    return *pixel_x >= 0 && *pixel_y >= 0 &&
        *pixel_x < width && *pixel_y < height;
}

static void jelly_probe_finish(struct priv *p)
{
    if (!p->jelly_probe_writer_ready || p->jelly_probe_finalized)
        return;
    p->jelly_probe_finalized = true;
    mpvrx_jelly_probe_writer_finish(&p->jelly_probe_writer,
                                    &p->jelly_probe_diagnostics);
    p->jelly_probe_armed = false;
}

static void jelly_probe_poll(struct priv *p)
{
    if (!p->jelly_probe_pending || !p->jelly_probe_buf || !p->jelly_probe_writer_ready)
        return;
    p->jelly_probe_diagnostics.gpu_poll_count++;
    if (!p->gpu) {
        p->jelly_probe_diagnostics.gpu_last_poll_status = MPVRX_JELLY_READBACK_ERROR;
        return;
    }
    if (pl_buf_poll(p->gpu, p->jelly_probe_buf, 0)) {
        p->jelly_probe_diagnostics.gpu_last_poll_status = MPVRX_JELLY_READBACK_PENDING;
        return;
    }
    p->jelly_probe_pending = false;
    p->jelly_probe_diagnostics.gpu_last_poll_status = MPVRX_JELLY_READBACK_READY;
    const float *record = (const float *) p->jelly_probe_buf->data;
    bool evaluated = mpvrx_jelly_probe_record_was_evaluated(record);
    p->jelly_probe_diagnostics.pixel_evaluated = evaluated ? 1 : 0;
    p->jelly_probe_last_record_valid = evaluated;
    if (evaluated) {
        p->jelly_probe_diagnostics.jelly_reason_code = isfinite(record[1]) ? (int)record[1] : -1;
        p->jelly_probe_diagnostics.jelly_fill = record[57 * 4 + 3];
        p->jelly_probe_diagnostics.activation = mpvrx_jelly_probe_record_is_activation(record) ? 1 : 0;
    }
    if (p->jelly_probe_diagnostics.activation == 1 &&
        mpvrx_jelly_probe_writer_capture(&p->jelly_probe_writer,
                                          p->jelly_probe_buf->data) == MPVRX_JELLY_CAPTURE_ACCEPTED)
        p->jelly_probe_armed = false;
}

'''
    source = replace_once(source, frame_anchor, poll + frame_anchor, "probe helpers insertion")
    render_mix_anchor = '''static bool render_source_mix(struct vo *vo, const struct vo_frame *frame,
                               const struct pl_frame_mix *mix,
                               const uint64_t *view_signatures,
                               const struct pl_frame *target,
                               const struct pl_render_params *params,
                               bool can_interpolate, int *motion_result)
{
    struct priv *p = vo->priv;
'''
    render_mix_probe = render_mix_anchor + '''    jelly_probe_poll(p);
    struct mpvrx_jelly_probe_diagnostics *probe_diag = &p->jelly_probe_diagnostics;
    if (p->jelly_probe_writer_ready && !p->jelly_probe_finalized) {
        probe_diag->render_source_mix_callback_count++;
        probe_diag->motion_views_enabled = p->motion_views ? 1 : 0;
    }
    bool probe_active = p->jelly_probe_armed && p->jelly_probe_writer_ready &&
        !p->jelly_probe_finalized && p->jelly_probe_buf && p->jelly_probe_motion;
'''
    source = replace_once(source, render_mix_anchor, render_mix_probe, "render-source-mix diagnostics")

    motion_mix_anchor = '''struct motion_mix {
    bool prepare_only;
    struct mp_motion *motion;
    float alpha;
    bool synthesized;
    int num_frames;
    enum mp_motion_result result;
};
'''
    motion_mix_probe = '''struct motion_mix {
    bool prepare_only;
    struct priv *probe_priv;
    bool probe_active, probe_candidate;
    struct mp_motion_probe_request probe_request;
    struct mp_motion *motion;
    float alpha;
    bool synthesized;
    int num_frames;
    enum mp_motion_result result;
};
'''
    source = replace_once(source, motion_mix_anchor, motion_mix_probe, "motion-view probe state")

    view_helper = '''static void jelly_probe_shader_view(struct motion_mix *mix,
                                   const struct pl_rendered_mix *images)
{
    struct priv *p = mix->probe_priv;
    if (!p || !images || images->num_frames != 2)
        return;
    struct mpvrx_jelly_probe_diagnostics *d = &p->jelly_probe_diagnostics;
    if (p->jelly_probe_writer_ready && !p->jelly_probe_finalized) {
        d->motion_view_callback_count++;
        d->motion_view_shader_callback_count++;
        d->motion_render_callback_count++;
        if (mix->probe_active)
            d->armed_render_callback_count++;
    }
    if (!mix->synthesized)
        return;
    if (!mix->probe_active || !mix->probe_candidate || !p->jelly_probe_motion)
        return;
    struct mp_motion_probe_request view_probe = mix->probe_request;
    const struct mp_motion_probe_request *probe = &view_probe;
    pl_tex view = images->frames[0].texture;
    int width = view ? view->params.w : 0;
    int height = view ? view->params.h : 0;
    bool pixel_in_bounds = view && jelly_probe_pixel_for_size(
        width, height, &view_probe.pixel_x, &view_probe.pixel_y);
    bool alpha_valid = isfinite(probe->alpha);
    unsigned rejection_mask = 0;
    if (!pixel_in_bounds) {
        d->out_of_bounds_count++;
        rejection_mask |= 1u;
    }
    if (!alpha_valid) {
        d->alpha_invalid_count++;
        rejection_mask |= 8u;
    } else if (probe->alpha < 0.35f) {
        d->alpha_below_count++;
        rejection_mask |= 2u;
    } else if (probe->alpha > 0.65f) {
        d->alpha_above_count++;
        rejection_mask |= 4u;
    }
    d->last_candidate_view_width = width;
    d->last_candidate_view_height = height;
    d->last_candidate_pixel_in_bounds = pixel_in_bounds ? 1 : 0;
    d->last_candidate_rejection_mask = rejection_mask;
    bool eligible = pixel_in_bounds && alpha_valid && probe->alpha >= 0.35f &&
        probe->alpha <= 0.65f;
    d->last_candidate_eligible = eligible ? 1 : 0;
    d->last_candidate_pending = eligible && p->jelly_probe_pending ? 1 : 0;
    if (!eligible)
        return;
    d->eligible_pair_count++;
    if (probe->selection_mode == 1)
        d->target_pair_count++;
    else
        d->fallback_pair_count++;
    if (p->jelly_probe_pending) {
        d->pending_readback_count++;
        return;
    }
    d->last_pair_observed = 1;
    d->last_pts_valid = d->last_candidate_pts_valid;
    d->last_pts[0] = probe->first_pts;
    d->last_pts[1] = probe->second_pts;
    d->last_alpha = probe->alpha;
    d->last_selection_mode = probe->selection_mode;
    d->render_attempt_count++;
    pl_tex probe_output = NULL;
    enum mp_motion_result result = mp_motion_render_views_probe(
        p->jelly_probe_motion, &images->frames[0], &images->frames[1],
        mix->alpha, probe, &probe_output);
    if (result == MP_MOTION_OK) {
        p->jelly_probe_pending = true;
        d->motion_view_probe_dispatch_count++;
        d->gpu_submit_count++;
        d->gpu_last_poll_status = MPVRX_JELLY_READBACK_PENDING;
    } else {
        d->render_failure_count++;
    }
}

'''
    shader_anchor = 'static pl_tex mix_motion_views(void *priv, const struct pl_rendered_mix *images)\n'
    source = replace_once(source, shader_anchor, view_helper + shader_anchor,
                          "shader-path Jelly probe helper")

    shader_view_anchor = '''    mix->result = mp_motion_append_view_shader(
        mix->motion, &images->frames[0], &images->frames[1],
        mix->alpha, shader);
    mix->synthesized = mix->result == MP_MOTION_OK;
    return mix->synthesized;
'''
    shader_view_probe = '''    mix->result = mp_motion_append_view_shader(
        mix->motion, &images->frames[0], &images->frames[1],
        mix->alpha, shader);
    mix->synthesized = mix->result == MP_MOTION_OK;
    jelly_probe_shader_view(mix, images);
    return mix->synthesized;
'''
    source = replace_once(source, shader_view_anchor, shader_view_probe,
                          "shader mixer diagnostic pass")

    view_render_anchor = '''    pl_tex output;
    mix->result = mp_motion_render_views(mix->motion, &images->frames[0],
                                         &images->frames[1], mix->alpha, &output);
    if (mix->result != MP_MOTION_OK)
        return NULL;
'''
    view_render_probe = '''    struct priv *p = mix->probe_priv;
    struct mpvrx_jelly_probe_diagnostics *d = p ? &p->jelly_probe_diagnostics : NULL;
    if (d && p->jelly_probe_writer_ready && !p->jelly_probe_finalized) {
        d->motion_view_callback_count++;
        d->motion_view_texture_callback_count++;
        d->motion_render_callback_count++;
        if (mix->probe_active)
            d->armed_render_callback_count++;
    }
    struct mp_motion_probe_request view_probe = mix->probe_request;
    const struct mp_motion_probe_request *probe = mix->probe_active && mix->probe_candidate
        ? &view_probe : NULL;
    bool dispatch_probe = false;
    if (probe && d) {
        pl_tex view = images->frames[0].texture;
        int width = view ? view->params.w : 0;
        int height = view ? view->params.h : 0;
        bool pixel_in_bounds = view && jelly_probe_pixel_for_size(
            width, height, &view_probe.pixel_x, &view_probe.pixel_y);
        bool alpha_valid = isfinite(probe->alpha);
        unsigned rejection_mask = 0;
        if (!pixel_in_bounds) {
            d->out_of_bounds_count++;
            rejection_mask |= 1u;
        }
        if (!alpha_valid) {
            d->alpha_invalid_count++;
            rejection_mask |= 8u;
        } else if (probe->alpha < 0.35f) {
            d->alpha_below_count++;
            rejection_mask |= 2u;
        } else if (probe->alpha > 0.65f) {
            d->alpha_above_count++;
            rejection_mask |= 4u;
        }
        d->last_candidate_observed = 1;
        d->last_candidate_pts_valid = isfinite(probe->first_pts) && isfinite(probe->second_pts);
        d->last_candidate_pts[0] = probe->first_pts;
        d->last_candidate_pts[1] = probe->second_pts;
        d->last_candidate_alpha = probe->alpha;
        d->last_candidate_selection_mode = probe->selection_mode;
        d->last_candidate_view_width = width;
        d->last_candidate_view_height = height;
        d->last_candidate_pixel_in_bounds = pixel_in_bounds ? 1 : 0;
        d->last_candidate_rejection_mask = rejection_mask;
        bool eligible = pixel_in_bounds && alpha_valid && probe->alpha >= 0.35f &&
            probe->alpha <= 0.65f;
        d->last_candidate_eligible = eligible ? 1 : 0;
        d->last_candidate_pending = eligible && p->jelly_probe_pending ? 1 : 0;
        if (eligible) {
            d->eligible_pair_count++;
            if (probe->selection_mode == 1)
                d->target_pair_count++;
            else
                d->fallback_pair_count++;
            if (p->jelly_probe_pending) {
                d->pending_readback_count++;
            } else if (p->jelly_probe_armed) {
                d->last_pair_observed = 1;
                d->last_pts_valid = d->last_candidate_pts_valid;
                d->last_pts[0] = probe->first_pts;
                d->last_pts[1] = probe->second_pts;
                d->last_alpha = probe->alpha;
                d->last_selection_mode = probe->selection_mode;
                d->render_attempt_count++;
                dispatch_probe = true;
            }
        }
    }
    pl_tex output;
    mix->result = mp_motion_render_views(mix->motion, &images->frames[0],
        &images->frames[1], mix->alpha, &output);
    if (dispatch_probe) {
        pl_tex probe_output = NULL;
        enum mp_motion_result probe_result = mp_motion_render_views_probe(
            p->jelly_probe_motion, &images->frames[0], &images->frames[1],
            mix->alpha, probe, &probe_output);
        if (probe_result == MP_MOTION_OK) {
            p->jelly_probe_pending = true;
            d->motion_view_probe_dispatch_count++;
            d->gpu_submit_count++;
            d->gpu_last_poll_status = MPVRX_JELLY_READBACK_PENDING;
        } else {
            d->render_failure_count++;
        }
    }
    if (mix->result != MP_MOTION_OK)
        return NULL;
'''
    source = replace_once(source, view_render_anchor, view_render_probe, "motion-view probe dispatch")

    pair_path_anchor = "        if (selected && motion_views_supported(p, mix, pair, target, params)) {\n"
    pair_path_probe = '''        bool views_supported = selected && motion_views_supported(p, mix, pair, target, params);
        if (selected && probe_diag->setup_status == MPVRX_JELLY_STAGE_OK &&
            !p->jelly_probe_finalized) {
            probe_diag->motion_view_pair_count++;
            if (views_supported)
                probe_diag->motion_view_supported_pair_count++;
            else
                probe_diag->motion_view_unsupported_pair_count++;
        }
        if (selected && probe_active) {
            const struct mp_image *probe_mpi0 = mix->frames[pair[0]]->user_data;
            const struct mp_image *probe_mpi1 = mix->frames[pair[1]]->user_data;
            const struct frame_priv *probe_fp0 = probe_mpi0->priv;
            const struct frame_priv *probe_fp1 = probe_mpi1->priv;
            bool valid_probe_pts = isfinite(probe_mpi0->pts) && isfinite(probe_mpi1->pts) &&
                probe_mpi0->pts != MP_NOPTS_VALUE && probe_mpi1->pts != MP_NOPTS_VALUE;
            bool target_probe_pair = valid_probe_pts;
            bool fallback_probe_pair = !valid_probe_pts;
            int probe_pixel_x = -1, probe_pixel_y = -1;
            bool source_pixel_in_bounds = jelly_probe_pixel_for_size(
                probe_mpi0->params.w, probe_mpi0->params.h,
                &probe_pixel_x, &probe_pixel_y);
            motion.probe_candidate = source_pixel_in_bounds;
            motion.probe_request = (struct mp_motion_probe_request) {
                .buffer = p->jelly_probe_buf, .pixel_x = probe_pixel_x, .pixel_y = probe_pixel_y,
                .first_id = probe_fp0->motion_id, .second_id = probe_fp1->motion_id,
                .first_pts = valid_probe_pts ? probe_mpi0->pts : NAN,
                .second_pts = valid_probe_pts ? probe_mpi1->pts : NAN,
                .alpha = motion.alpha,
                .selection_mode = target_probe_pair ? 1 : fallback_probe_pair ? 2 : 0,
            };
            probe_diag->last_candidate_observed = 1;
            probe_diag->last_candidate_pts_valid = valid_probe_pts ? 1 : 0;
            probe_diag->last_candidate_pts[0] = valid_probe_pts ? probe_mpi0->pts : NAN;
            probe_diag->last_candidate_pts[1] = valid_probe_pts ? probe_mpi1->pts : NAN;
            probe_diag->last_candidate_alpha = motion.alpha;
            probe_diag->last_candidate_selection_mode = target_probe_pair ? 1 : 2;
            probe_diag->source_bounds_observed = 1;
            probe_diag->pixel_x = probe_pixel_x;
            probe_diag->pixel_y = probe_pixel_y;
            probe_diag->source_width = probe_mpi0->params.w;
            probe_diag->source_height = probe_mpi0->params.h;
            probe_diag->pixel_in_bounds = source_pixel_in_bounds ? 1 : 0;
            probe_diag->last_candidate_source_width = probe_mpi0->params.w;
            probe_diag->last_candidate_source_height = probe_mpi0->params.h;
            probe_diag->last_candidate_pixel_in_bounds = source_pixel_in_bounds ? 1 : 0;
            probe_diag->last_candidate_view_width = -1;
            probe_diag->last_candidate_view_height = -1;
            probe_diag->last_candidate_eligible = 0;
            probe_diag->last_candidate_pending = 0;
            probe_diag->last_candidate_rejection_mask = source_pixel_in_bounds ? 0 : 1u;
            if (!source_pixel_in_bounds)
                probe_diag->out_of_bounds_count++;
        }
        if (views_supported) {
'''
    source = replace_once(source, pair_path_anchor, pair_path_probe, "motion-view pair diagnostics")
    source = replace_once(source,
        '        struct motion_mix motion = {.motion = p->motion, .num_frames = -1};\n',
        '        struct motion_mix motion = {\n'
        '            .probe_priv = p, .probe_active = probe_active,\n'
        '            .motion = p->motion, .num_frames = -1,\n'
        '        };\n',
        "motion-view probe state initializer")

    call_anchor = '''    if (described) {
        enum mp_motion_result result = mp_motion_render_frames(
            p->motion, &fp0->motion_frame, &fp1->motion_frame, alpha, output);
'''
    probe_call = '''    bool valid_probe_pts = isfinite(mpi0->pts) && isfinite(mpi1->pts) &&
        mpi0->pts != MP_NOPTS_VALUE && mpi1->pts != MP_NOPTS_VALUE;
    bool target_probe_pair = valid_probe_pts;
    bool fallback_probe_pair = !valid_probe_pts || !target_probe_pair;
    struct mpvrx_jelly_probe_diagnostics *probe_diag = &p->jelly_probe_diagnostics;
    bool probe_active = p->jelly_probe_armed && p->jelly_probe_writer_ready &&
        !p->jelly_probe_finalized && p->jelly_probe_buf && p->jelly_probe_motion;
    if (probe_diag->setup_status == MPVRX_JELLY_STAGE_OK && !p->jelly_probe_finalized)
        probe_diag->motion_render_callback_count++;
    int probe_pixel_x = -1, probe_pixel_y = -1;
    bool pixel_in_bounds = jelly_probe_pixel_for_size(
        mpi0->params.w, mpi0->params.h, &probe_pixel_x, &probe_pixel_y);
    if (probe_active) {
        probe_diag->armed_render_callback_count++;
        probe_diag->source_bounds_observed = 1;
        probe_diag->pixel_x = probe_pixel_x;
        probe_diag->pixel_y = probe_pixel_y;
        probe_diag->source_width = mpi0->params.w;
        probe_diag->source_height = mpi0->params.h;
        probe_diag->pixel_in_bounds = pixel_in_bounds ? 1 : 0;
        unsigned rejection_mask = 0;
        if (!pixel_in_bounds) {
            probe_diag->out_of_bounds_count++;
            rejection_mask |= 1u;
        }
        if (!isfinite(alpha)) {
            probe_diag->alpha_invalid_count++;
            rejection_mask |= 8u;
        } else if (alpha < 0.35f) {
            probe_diag->alpha_below_count++;
            rejection_mask |= 2u;
        } else if (alpha > 0.65f) {
            probe_diag->alpha_above_count++;
            rejection_mask |= 4u;
        }
        probe_diag->last_candidate_observed = 1;
        probe_diag->last_candidate_pts_valid = valid_probe_pts ? 1 : 0;
        probe_diag->last_candidate_pts[0] = valid_probe_pts ? mpi0->pts : NAN;
        probe_diag->last_candidate_pts[1] = valid_probe_pts ? mpi1->pts : NAN;
        probe_diag->last_candidate_alpha = alpha;
        probe_diag->last_candidate_selection_mode = target_probe_pair ? 1 : 2;
        probe_diag->last_candidate_source_width = mpi0->params.w;
        probe_diag->last_candidate_source_height = mpi0->params.h;
        probe_diag->last_candidate_pixel_in_bounds = pixel_in_bounds ? 1 : 0;
        probe_diag->last_candidate_rejection_mask = rejection_mask;
    }
    bool frame_pair_eligible = probe_active && pixel_in_bounds &&
        (target_probe_pair || fallback_probe_pair) && alpha >= 0.35f && alpha <= 0.65f;
    if (probe_active) {
        probe_diag->last_candidate_eligible = frame_pair_eligible ? 1 : 0;
        probe_diag->last_candidate_pending = frame_pair_eligible && p->jelly_probe_pending ? 1 : 0;
        if (frame_pair_eligible && p->jelly_probe_pending)
            probe_diag->pending_readback_count++;
    }
    if (frame_pair_eligible) {
        probe_diag->eligible_pair_count++;
        if (target_probe_pair) probe_diag->target_pair_count++;
        else probe_diag->fallback_pair_count++;
    }
    bool probe_pair = frame_pair_eligible && !p->jelly_probe_pending;
    if (probe_pair) {
        probe_diag->last_pair_observed = 1;
        probe_diag->last_pts_valid = valid_probe_pts ? 1 : 0;
        probe_diag->last_pts[0] = valid_probe_pts ? mpi0->pts : NAN;
        probe_diag->last_pts[1] = valid_probe_pts ? mpi1->pts : NAN;
        probe_diag->last_alpha = alpha;
        probe_diag->last_selection_mode = target_probe_pair ? 1 : 2;
        probe_diag->render_attempt_count++;
    }
    struct mp_motion_probe_request probe_request = {
        .buffer = p->jelly_probe_buf, .pixel_x = probe_pixel_x, .pixel_y = probe_pixel_y,
        .first_id = fp0->motion_id, .second_id = fp1->motion_id,
        .first_pts = isfinite(mpi0->pts) && mpi0->pts != MP_NOPTS_VALUE ? mpi0->pts : NAN,
        .second_pts = isfinite(mpi1->pts) && mpi1->pts != MP_NOPTS_VALUE ? mpi1->pts : NAN,
        .alpha = alpha, .selection_mode = target_probe_pair ? 1 : 2,
    };
    if (described) {
        enum mp_motion_result result = probe_pair
            ? mp_motion_render_frames_probe(p->motion, &fp0->motion_frame,
                &fp1->motion_frame, alpha, &probe_request, output)
            : mp_motion_render_frames(p->motion, &fp0->motion_frame,
                &fp1->motion_frame, alpha, output);
        if (probe_pair && result == MP_MOTION_OK) {
            p->jelly_probe_pending = true;
            probe_diag->gpu_submit_count++;
            probe_diag->gpu_last_poll_status = MPVRX_JELLY_READBACK_PENDING;
        } else if (probe_pair) {
            probe_diag->render_failure_count++;
        }
'''
    source = replace_once(source, call_anchor, probe_call, "described motion call")
    raw_anchor = '''        ok = mp_motion_render(p->motion, fp0->motion_texture, fp1->motion_texture,
                              fp0->motion_id, fp1->motion_id, alpha, &texture);
'''
    raw_new = '''        ok = probe_pair
            ? mp_motion_render_probe(p->motion, fp0->motion_texture, fp1->motion_texture,
                fp0->motion_id, fp1->motion_id, alpha, &probe_request, &texture)
            : mp_motion_render(p->motion, fp0->motion_texture, fp1->motion_texture,
                fp0->motion_id, fp1->motion_id, alpha, &texture);
        if (probe_pair && ok) {
            p->jelly_probe_pending = true;
            probe_diag->gpu_submit_count++;
            probe_diag->gpu_last_poll_status = MPVRX_JELLY_READBACK_PENDING;
        } else if (probe_pair) {
            probe_diag->render_failure_count++;
        }
'''
    source = replace_once(source, raw_anchor, raw_new, "raw motion call")

    dtor_anchor = 'static void uninit(struct vo *vo)\n'
    destructor = '''static void jelly_probe_priv_destroy(void *opaque)
{
    struct priv *p = opaque;
    if (!p->jelly_probe_writer_ready)
        return;
    jelly_probe_finish(p);
    mpvrx_jelly_probe_writer_join(&p->jelly_probe_writer);
    mpvrx_jelly_probe_writer_destroy(&p->jelly_probe_writer);
    p->jelly_probe_writer_ready = false;
}

'''
    source = replace_once(source, dtor_anchor, destructor + dtor_anchor, "priv destructor")
    uninit_anchor = '    if (p->gpu)\n        pl_gpu_finish(p->gpu);\n'
    uninit_new = '''    if (p->gpu)
        pl_gpu_finish(p->gpu);
    jelly_probe_poll(p);
    if (p->jelly_probe_armed && p->jelly_probe_last_record_valid &&
        p->jelly_probe_buf && p->jelly_probe_writer_ready &&
        mpvrx_jelly_probe_writer_capture(&p->jelly_probe_writer,
                                          p->jelly_probe_buf->data) == MPVRX_JELLY_CAPTURE_ACCEPTED)
        p->jelly_probe_armed = false;
    p->jelly_probe_armed = false;
    jelly_probe_finish(p);
    mp_motion_destroy(&p->jelly_probe_motion);
    if (p->gpu)
        pl_buf_destroy(p->gpu, &p->jelly_probe_buf);
'''
    source = replace_once(source, uninit_anchor, uninit_new, "GPU-safe uninit")
    # The VO's talloc-owned priv destructor runs after vo_destroy joined its thread.
    source = replace_once(source, '    struct priv *p = vo->priv;\n',
        '    struct priv *p = vo->priv;\n', "priv setup anchor") if False else source
    # Registered from the existing preinit once p is allocated.
    preinit = '    struct priv *p = vo->priv;\n'
    first = source.find(preinit)
    if first < 0:
        raise ValueError("preinit priv initialization anchor missing")
    # locate the actual preinit function before editing to avoid unrelated handlers
    start = source.find('static int preinit(struct vo *vo)')
    if start < 0:
        raise ValueError("preinit anchor missing")
    pos = source.find(preinit, start)
    source = source[:pos] + source[pos:].replace(preinit,
        preinit + '    talloc_set_destructor(p, jelly_probe_priv_destroy);\n', 1)

    meson_anchor = "    'video/out/vo_gpu_next.c',\n"
    meson = replace_once(meson, meson_anchor, meson_anchor +
        "    'video/out/mpvrx_jelly_probe_writer.c',\n    # " + MARKER + "\n", "Meson source")
    shutil.copy2(header, vo_path.parent / header.name)
    shutil.copy2(implementation, vo_path.parent / implementation.name)
    vo_path.write_text(source)
    meson_file.write_text(meson)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('vo_source_path', type=Path)
    ap.add_argument('meson_path', type=Path)
    ap.add_argument('repo_writer_dir', type=Path)
    ap.add_argument('--probe-enabled', action='store_true')
    args = ap.parse_args()
    apply_patch(args.vo_source_path, args.meson_path, args.repo_writer_dir,
                args.probe_enabled)

if __name__ == '__main__':
    main()
