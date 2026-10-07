/*
 * This file is part of mpv.
 *
 * mpv is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * The MPVFlow implementation below is an independent, clean-room MEMC
 * experiment and does not include SVPFlow or RIFE code.
 */
#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "common/common.h"
#include "filters/filter.h"
#include "filters/filter_internal.h"
#include "filters/user_filters.h"
#include "options/m_option.h"
#include "osdep/timer.h"
#include "video/img_format.h"
#include "video/mp_image.h"
#include "video/sws_utils.h"
#include "mpvflow_core.h"
#define MPVFLOW_MAX_SYNTH_PER_GAP 12
#define MPVFLOW_FALLBACK_QUEUE_SIZE 4
struct f_opts {
    double target_fps;
    int max_dimension;
    int block_size;
    int search_radius;
};
#define OPT_BASE_STRUCT struct f_opts
static const struct m_option f_opts_list[] = {
    {"target-fps", OPT_DOUBLE(target_fps), M_RANGE(1.0, 240.0)},
    {"max-dimension", OPT_INT(max_dimension), M_RANGE(-1, 8192)},
    {"block-size", OPT_INT(block_size), M_RANGE(8, 16)},
    {"search-radius", OPT_INT(search_radius), M_RANGE(1, 32)},
    {0}
};
static const struct f_opts f_opts_def = {
    .target_fps = 60.0,
    .max_dimension = 0,
    .block_size = 8,
    .search_radius = 8,
};
struct priv {
    struct f_opts *opts;
    MPVFlowContext *engine;
    MPVFlowPair *motion;
    struct mp_sws_context *sws;
    struct mp_frame prev;
    struct mp_frame pending;
    bool prev_was_emitted;
    bool active;
    bool prepared;
    bool pair_is_scene_cut;
    double next_pts;
    double frame_step;
    unsigned int outputs_in_gap;
    int process_width;
    int process_height;
    size_t packed_bytes;
    uint8_t *packed0;
    uint8_t *packed1;
    uint8_t *packed_out;
    int64_t analysis_ns;
    int64_t synthesis_ns;
    int64_t pair_work_ns;
    double pair_budget_ms;
    unsigned int timing_count;
    int adaptive_max_dimension;
    unsigned int adaptive_recovery_count;
    int64_t last_source_rate_guard_ns;
    struct mp_frame fallback[MPVFLOW_FALLBACK_QUEUE_SIZE];
    int fallback_count;
    int fallback_pos;
};
static struct mp_image *frame_image(struct mp_frame frame)
{
    return frame.type == MP_FRAME_VIDEO ? frame.data : NULL;
}
static bool pts_is_valid(double pts)
{
    return isfinite(pts) && pts != MP_NOPTS_VALUE;
}
static const char *image_unsupported_reason(struct mp_image *img)
{
    if (!img)
        return "non_video_frame";
    if (img->w < 8 || img->h < 8)
        return "dimensions_below_block_size";
    if (img->fields & MP_IMGFIELD_INTERLACED)
        return "interlaced";
    if (img->hwctx || IMGFMT_IS_HWACCEL(img->imgfmt))
        return "hardware_only_frame";
    if (img->params.color.transfer == PL_COLOR_TRC_PQ)
        return "hdr_pq";
    if (img->params.color.transfer == PL_COLOR_TRC_HLG)
        return "hdr_hlg";
    if (!(img->fmt.flags & MP_IMGFLAG_HAS_COMPS))
        return "unknown_pixel_format";
    return NULL;
}
static int effective_max_dimension(struct priv *p)
{
    if (p->opts->max_dimension < 0)
        return 0;
    if (p->opts->max_dimension > 0)
        return p->opts->max_dimension;
    if (p->opts->target_fps >= 144.0)
        return 240;
    if (p->opts->target_fps >= 96.0)
        return 320;
    if (p->opts->target_fps >= 72.0)
        return 360;
    return 480;
}
static void processing_dimensions(struct priv *p, int width, int height,
                                  int *process_width, int *process_height)
{
    int limit = effective_max_dimension(p);
    if (p->adaptive_max_dimension > 0 &&
        (limit <= 0 || p->adaptive_max_dimension < limit))
        limit = p->adaptive_max_dimension;
    int longest = width > height ? width : height;
    double scale = limit > 0 && longest > limit ? limit / (double)longest : 1.0;
    *process_width = (int)lround(width * scale);
    *process_height = (int)lround(height * scale);
    if (*process_width < 8)
        *process_width = 8;
    if (*process_height < 8)
        *process_height = 8;
}
static void clear_pair_cache(struct priv *p)
{
    mpvflow_pair_destroy(p->motion);
    p->motion = NULL;
    free(p->packed0);
    free(p->packed1);
    free(p->packed_out);
    p->packed0 = NULL;
    p->packed1 = NULL;
    p->packed_out = NULL;
    p->packed_bytes = 0;
    p->process_width = 0;
    p->process_height = 0;
    p->analysis_ns = 0;
    p->synthesis_ns = 0;
    p->pair_work_ns = 0;
    p->pair_budget_ms = 0;
    p->timing_count = 0;
    p->prepared = false;
    p->pair_is_scene_cut = false;
}
static void clear_frames(struct priv *p)
{
    mp_frame_unref(&p->prev);
    mp_frame_unref(&p->pending);
    for (int n = 0; n < p->fallback_count; n++)
        mp_frame_unref(&p->fallback[n]);
    p->fallback_count = 0;
    p->fallback_pos = 0;
    p->prev_was_emitted = false;
    p->next_pts = 0;
    p->outputs_in_gap = 0;
    p->last_source_rate_guard_ns = 0;
    clear_pair_cache(p);
}
static void fallback_add(struct priv *p, struct mp_frame frame)
{
    if (!frame.type || p->fallback_count >= MPVFLOW_FALLBACK_QUEUE_SIZE) {
        mp_frame_unref(&frame);
        return;
    }
    p->fallback[p->fallback_count++] = frame;
}
static void report_pair_timing(struct mp_filter *f);
static bool write_fallback(struct mp_filter *f)
{
    struct priv *p = f->priv;
    if (p->fallback_pos >= p->fallback_count)
        return false;
    struct mp_frame frame = p->fallback[p->fallback_pos];
    p->fallback[p->fallback_pos++] = MP_NO_FRAME;
    if (p->fallback_pos == p->fallback_count) {
        p->fallback_count = 0;
        p->fallback_pos = 0;
    }
    mp_pin_in_write(f->ppins[1], frame);
    return true;
}
static void disable_and_queue(struct mp_filter *f, const char *reason,
                              struct mp_frame current)
{
    struct priv *p = f->priv;
    MP_WARN(f, "MPVFLOW_DIAGNOSTIC event=disabled reason=%s target_fps=%.0f outputs_in_gap=%u\n",
            reason, p->opts->target_fps, p->outputs_in_gap);
    if (p->prev.type && !p->prev_was_emitted && p->outputs_in_gap == 0)
        fallback_add(p, mp_frame_ref(p->prev));
    if (p->pending.type) {
        fallback_add(p, p->pending);
        p->pending = MP_NO_FRAME;
    }
    if (current.type)
        fallback_add(p, current);
    mp_frame_unref(&p->prev);
    report_pair_timing(f);
    clear_pair_cache(p);
    p->active = false;
    p->outputs_in_gap = 0;
}
static void bypass_unsupported(struct mp_filter *f, struct mp_image *img,
                               const char *reason)
{
    struct priv *p = f->priv;
    MP_WARN(f, "MPVFLOW_DIAGNOSTIC event=passthrough reason=%s format=%s width=%d height=%d\n",
            reason, img ? mp_imgfmt_to_name(img->imgfmt) : "none",
            img ? img->w : 0, img ? img->h : 0);
    if (p->prev.type && !p->prev_was_emitted && p->outputs_in_gap == 0)
        fallback_add(p, mp_frame_ref(p->prev));
    if (p->pending.type) {
        fallback_add(p, p->pending);
        p->pending = MP_NO_FRAME;
    }
    mp_frame_unref(&p->prev);
    p->prev = MP_NO_FRAME;
    report_pair_timing(f);
    clear_pair_cache(p);
    p->next_pts = 0;
    p->prev_was_emitted = false;
    p->outputs_in_gap = 0;
    p->active = false;
}
static bool convert_to_rgb(struct priv *p, struct mp_image *src,
                           struct mp_image *rgb)
{
    mp_image_copy_attributes(rgb, src);
    return mp_sws_scale(p->sws, rgb, src) >= 0;
}
static bool pack_rgb24(struct mp_image *rgb, uint8_t *packed, size_t row_bytes)
{
    if (!rgb || rgb->imgfmt != IMGFMT_RGB24 || !rgb->planes[0] ||
        abs(rgb->stride[0]) < row_bytes)
        return false;
    for (int y = 0; y < rgb->h; y++) {
        const uint8_t *row = rgb->planes[0] + (ptrdiff_t)y * rgb->stride[0];
        memcpy(packed + (size_t)y * row_bytes, row, row_bytes);
    }
    return true;
}
static bool unpack_rgb24(const uint8_t *packed, struct mp_image *rgb,
                         size_t row_bytes)
{
    if (!rgb || rgb->imgfmt != IMGFMT_RGB24 || !rgb->planes[0] ||
        abs(rgb->stride[0]) < row_bytes)
        return false;
    for (int y = 0; y < rgb->h; y++) {
        uint8_t *row = rgb->planes[0] + (ptrdiff_t)y * rgb->stride[0];
        memcpy(row, packed + (size_t)y * row_bytes, row_bytes);
    }
    return true;
}
static bool prepare_pair(struct mp_filter *f, struct mp_image *a,
                         struct mp_image *b)
{
    struct priv *p = f->priv;
    int64_t work_start = mp_time_ns();
    processing_dimensions(p, a->w, a->h, &p->process_width, &p->process_height);
    size_t width = (size_t)p->process_width;
    size_t height = (size_t)p->process_height;
    if (width > SIZE_MAX / 3 || height > SIZE_MAX / (width * 3))
        return false;
    size_t row_bytes = width * 3;
    p->packed_bytes = row_bytes * height;
    p->packed0 = malloc(p->packed_bytes);
    p->packed1 = malloc(p->packed_bytes);
    p->packed_out = malloc(p->packed_bytes);
    struct mp_image *rgb0 = mp_image_alloc(IMGFMT_RGB24, p->process_width,
                                           p->process_height);
    struct mp_image *rgb1 = mp_image_alloc(IMGFMT_RGB24, p->process_width,
                                           p->process_height);
    if (!p->packed0 || !p->packed1 || !p->packed_out || !rgb0 || !rgb1) {
        talloc_free(rgb0);
        talloc_free(rgb1);
        clear_pair_cache(p);
        return false;
    }
    bool converted = convert_to_rgb(p, a, rgb0) && convert_to_rgb(p, b, rgb1) &&
        pack_rgb24(rgb0, p->packed0, row_bytes) &&
        pack_rgb24(rgb1, p->packed1, row_bytes);
    talloc_free(rgb0);
    talloc_free(rgb1);
    if (!converted) {
        clear_pair_cache(p);
        return false;
    }
    int64_t start = mp_time_ns();
    struct MPVFlowStats stats = {0};
    int result = mpvflow_analyze_pair(p->engine, p->packed0, p->packed1,
                                      p->process_width, p->process_height,
                                      &p->motion, &stats);
    p->analysis_ns = mp_time_ns() - start;
    if (result == MPVFLOW_ERROR || !p->motion) {
        clear_pair_cache(p);
        return false;
    }
    p->pair_is_scene_cut = result == MPVFLOW_SCENE_CUT;
    p->prepared = true;
    MP_INFO(f, "MPVFLOW_DIAGNOSTIC event=vector_analysis width=%d height=%d source_width=%d source_height=%d target_fps=%.0f analysis_ms=%.2f mean_sad=%.2f mean_motion_px=%.2f scene_cut=%d\n",
            p->process_width, p->process_height, a->w, a->h,
            p->opts->target_fps, p->analysis_ns / 1e6,
            stats.mean_block_sad, stats.mean_motion_pixels,
            p->pair_is_scene_cut);
    p->pair_work_ns += mp_time_ns() - work_start;
    return true;
}
static struct mp_image *interpolate(struct mp_filter *f, struct mp_image *a,
                                    struct mp_image *b, double pts)
{
    struct priv *p = f->priv;
    if (!p->prepared && !prepare_pair(f, a, b)) {
        MP_WARN(f, "MPVFLOW_DIAGNOSTIC event=processing_error reason=pair_analysis_failed\n");
        return NULL;
    }
    int64_t synthesis_work_start = mp_time_ns();
    struct mp_image *rgb_out = mp_image_alloc(IMGFMT_RGB24,
                                               p->process_width,
                                               p->process_height);
    if (!rgb_out)
        return NULL;
    double span = b->pts - a->pts;
    double timestep = (pts - a->pts) / span;
    if (!isfinite(timestep) || timestep <= 0.0 || timestep >= 1.0) {
        talloc_free(rgb_out);
        return NULL;
    }
    int64_t start = mp_time_ns();
    struct MPVFlowStats stats = {0};
    int result = mpvflow_synthesize_rgb24(p->engine, p->motion,
                                          p->packed0, p->packed1,
                                          (float)timestep, p->packed_out,
                                          &stats);
    int64_t elapsed = mp_time_ns() - start;
    p->synthesis_ns += elapsed;
    p->timing_count++;
    if (result == MPVFLOW_ERROR ||
        !unpack_rgb24(p->packed_out, rgb_out,
                      (size_t)p->process_width * 3)) {
        talloc_free(rgb_out);
        return NULL;
    }
    struct mp_image *out = mp_image_alloc(a->imgfmt, a->w, a->h);
    if (!out) {
        talloc_free(rgb_out);
        return NULL;
    }
    mp_image_copy_attributes(out, a);
    mp_image_copy_attributes(rgb_out, a);
    if (mp_sws_scale(p->sws, out, rgb_out) < 0) {
        talloc_free(out);
        talloc_free(rgb_out);
        return NULL;
    }
    out->pts = pts;
    talloc_free(rgb_out);
    p->pair_work_ns += mp_time_ns() - synthesis_work_start;
    return out;
}
static void report_pair_timing(struct mp_filter *f)
{
    struct priv *p = f->priv;
    if (!p->timing_count)
        return;
    double processing_ms = p->pair_work_ns / 1e6;
    double budget_ratio = p->pair_budget_ms > 0
        ? processing_ms / p->pair_budget_ms : 0;
    MP_INFO(f, "MPVFLOW_DIAGNOSTIC event=source_pair_synthesis frames=%u analysis_ms=%.2f average_core_synthesis_ms=%.2f total_core_synthesis_ms=%.2f processing_ms=%.2f pair_budget_ms=%.2f budget_ratio=%.3f target_fps=%.0f process_width=%d process_height=%d scene_cut=%d\n",
            p->timing_count, p->analysis_ns / 1e6,
            p->synthesis_ns / (double)p->timing_count / 1e6,
            p->synthesis_ns / 1e6, processing_ms, p->pair_budget_ms,
            budget_ratio, p->opts->target_fps,
            p->process_width, p->process_height, p->pair_is_scene_cut);
    if (p->opts->max_dimension == 0 && p->adaptive_max_dimension > 0) {
        int previous_dimension = p->adaptive_max_dimension;
        int next_dimension = mpvflow_update_adaptive_dimension(
            previous_dimension, effective_max_dimension(p), budget_ratio,
            &p->adaptive_recovery_count);
        if (next_dimension != previous_dimension) {
            p->adaptive_max_dimension = next_dimension;
            MP_INFO(f, "MPVFLOW_DIAGNOSTIC event=resolution_governor reason=%s from_dimension=%d to_dimension=%d budget_ratio=%.3f target_fps=%.0f\n",
                    budget_ratio >= 0.85 ? "budget_pressure" : "sustained_headroom",
                    previous_dimension, next_dimension, budget_ratio,
                    p->opts->target_fps);
        }
    }
}
static void promote_pending(struct mp_filter *f, bool emitted)
{
    struct priv *p = f->priv;
    report_pair_timing(f);
    clear_pair_cache(p);
    mp_frame_unref(&p->prev);
    p->prev = mp_frame_ref(p->pending);
    mp_frame_unref(&p->pending);
    p->prev_was_emitted = emitted;
    p->outputs_in_gap = 0;
}
static void passthrough_high_rate_pair(struct mp_filter *f)
{
    struct priv *p = f->priv;
    struct mp_image *source = frame_image(p->pending);
    if (!source) {
        disable_and_queue(f, "high_rate_source_missing_frame", MP_NO_FRAME);
        write_fallback(f);
        return;
    }
    if (p->prev.type && !p->prev_was_emitted && p->outputs_in_gap == 0)
        fallback_add(p, mp_frame_ref(p->prev));
    struct mp_frame source_frame = p->pending;
    p->pending = MP_NO_FRAME;
    fallback_add(p, mp_frame_ref(source_frame));
    report_pair_timing(f);
    clear_pair_cache(p);
    mp_frame_unref(&p->prev);
    p->prev = source_frame;
    p->prev_was_emitted = true;
    p->next_pts = source->pts + p->frame_step;
    p->outputs_in_gap = 0;
    write_fallback(f);
}
static void yield_unfinished_pair(struct mp_filter *f)
{
    struct priv *p = f->priv;
    struct mp_image *source = frame_image(p->pending);
    if (!source) {
        disable_and_queue(f, "deadline_source_missing_frame", MP_NO_FRAME);
        write_fallback(f);
        return;
    }
    MP_WARN(f, "MPVFLOW_DIAGNOSTIC event=deadline_yield processing_ms=%.2f pair_budget_ms=%.2f source_pts=%.6f skipped_outputs=%u target_fps=%.0f\n",
            p->pair_work_ns / 1e6, p->pair_budget_ms, source->pts,
            p->outputs_in_gap, p->opts->target_fps);
    struct mp_frame source_frame = p->pending;
    p->pending = MP_NO_FRAME;
    fallback_add(p, mp_frame_ref(source_frame));
    report_pair_timing(f);
    clear_pair_cache(p);
    mp_frame_unref(&p->prev);
    p->prev = source_frame;
    p->prev_was_emitted = true;
    double tolerance = fmax(1e-7, p->frame_step * 1e-6);
    while (p->next_pts <= source->pts + tolerance)
        p->next_pts += p->frame_step;
    p->outputs_in_gap = 0;
}
static bool frames_compatible(struct mp_image *a, struct mp_image *b)
{
    return a && b && a->w == b->w && a->h == b->h && a->imgfmt == b->imgfmt;
}
static void f_process(struct mp_filter *f)
{
    struct priv *p = f->priv;
    if (!mp_pin_in_needs_data(f->ppins[1]))
        return;
    if (write_fallback(f))
        return;
    if (!p->active) {
        mp_pin_transfer_data(f->ppins[1], f->ppins[0]);
        return;
    }
    for (;;) {
        if (!p->prev.type) {
            struct mp_frame frame = mp_pin_out_read(f->ppins[0]);
            if (!frame.type)
                return;
            if (mp_frame_is_signaling(frame)) {
                mp_pin_in_write(f->ppins[1], frame);
                return;
            }
            struct mp_image *img = frame_image(frame);
            const char *unsupported = image_unsupported_reason(img);
            if (unsupported || !pts_is_valid(img->pts)) {
                mp_pin_in_write(f->ppins[1], frame);
                return;
            }
            p->prev = mp_frame_ref(frame);
            p->prev_was_emitted = true;
            p->next_pts = img->pts + p->frame_step;
            mp_pin_in_write(f->ppins[1], frame);
            return;
        }
        if (!p->pending.type) {
            struct mp_frame frame = mp_pin_out_read(f->ppins[0]);
            if (!frame.type)
                return;
            if (mp_frame_is_signaling(frame)) {
                if (frame.type == MP_FRAME_EOF && !p->prev_was_emitted &&
                    p->outputs_in_gap == 0) {
                    fallback_add(p, mp_frame_ref(p->prev));
                    mp_frame_unref(&p->prev);
                    fallback_add(p, frame);
                    p->active = false;
                    write_fallback(f);
                    return;
                }
                mp_frame_unref(&p->prev);
                clear_pair_cache(p);
                mp_pin_in_write(f->ppins[1], frame);
                return;
            }
            p->pending = frame;
        }
        struct mp_image *a = frame_image(p->prev);
        struct mp_image *b = frame_image(p->pending);
        const char *unsupported = image_unsupported_reason(b);
        if (unsupported || !frames_compatible(a, b) || !pts_is_valid(b->pts) ||
            b->pts <= a->pts) {
            const char *reason = unsupported ? unsupported :
                !pts_is_valid(b->pts) ? "missing_pts" :
                b->pts <= a->pts ? "non_monotonic_pts" : "incompatible_frames";
            bypass_unsupported(f, b, reason);
            write_fallback(f);
            return;
        }
        double source_delta = b->pts - a->pts;
        if (mpvflow_should_bypass_for_source_rate(source_delta, p->frame_step)) {
            int64_t now_ns = mp_time_ns();
            if (!p->last_source_rate_guard_ns ||
                now_ns - p->last_source_rate_guard_ns >= 1000000000LL) {
                MP_INFO(f, "MPVFLOW_DIAGNOSTIC event=source_rate_guard source_delta_ms=%.3f target_step_ms=%.3f target_fps=%.0f action=passthrough_original_pts\n",
                        source_delta * 1000.0, p->frame_step * 1000.0,
                        p->opts->target_fps);
                p->last_source_rate_guard_ns = now_ns;
            }
            passthrough_high_rate_pair(f);
            return;
        }
        p->last_source_rate_guard_ns = 0;
        double tolerance = fmax(1e-7, p->frame_step * 1e-6);
        if (p->next_pts < b->pts - tolerance) {
            double count = ceil((b->pts - p->next_pts - tolerance) /
                                p->frame_step);
            if (!isfinite(count) || count > MPVFLOW_MAX_SYNTH_PER_GAP) {
                disable_and_queue(f, "source_gap_exceeds_synthesis_limit",
                                  MP_NO_FRAME);
                write_fallback(f);
                return;
            }
            if (!p->prepared)
                p->pair_budget_ms = (b->pts - a->pts) * 1000.0;
            struct mp_image *out = interpolate(f, a, b, p->next_pts);
            if (!out) {
                disable_and_queue(f, "conversion_or_motion_synthesis_failed",
                                  MP_NO_FRAME);
                write_fallback(f);
                return;
            }
            struct mp_frame output = MAKE_FRAME(MP_FRAME_VIDEO, out);
            p->next_pts += p->frame_step;
            p->outputs_in_gap++;
            double processing_ms = p->pair_work_ns / 1e6;
            double pair_budget_ms = p->pair_budget_ms;
            bool outputs_remain = p->next_pts < b->pts - tolerance;
            if (mpvflow_should_yield_for_deadline(processing_ms,
                                                  pair_budget_ms,
                                                  outputs_remain)) {
                yield_unfinished_pair(f);
            }
            mp_pin_in_write(f->ppins[1], output);
            return;
        }
        if (fabs(p->next_pts - b->pts) <= tolerance) {
            struct mp_frame output = p->pending;
            p->pending = MP_NO_FRAME;
            report_pair_timing(f);
            clear_pair_cache(p);
            mp_frame_unref(&p->prev);
            p->prev = mp_frame_ref(output);
            p->prev_was_emitted = true;
            p->next_pts += p->frame_step;
            p->outputs_in_gap = 0;
            mp_pin_in_write(f->ppins[1], output);
            return;
        }
        promote_pending(f, false);
    }
}
static void f_reset(struct mp_filter *f)
{
    struct priv *p = f->priv;
    clear_frames(p);
    p->active = p->engine != NULL;
}
static void f_destroy(struct mp_filter *f)
{
    struct priv *p = f->priv;
    clear_frames(p);
    mpvflow_destroy(p->engine);
    p->engine = NULL;
}
static const struct mp_filter_info filter = {
    .name = "mpvflow",
    .process = f_process,
    .reset = f_reset,
    .destroy = f_destroy,
    .priv_size = sizeof(struct priv),
};
static struct mp_filter *f_create(struct mp_filter *parent, void *options)
{
    struct mp_filter *f = mp_filter_create(parent, &filter);
    if (!f) {
        talloc_free(options);
        return NULL;
    }
    struct priv *p = f->priv;
    p->opts = talloc_steal(p, options);
    p->frame_step = 1.0 / p->opts->target_fps;
    p->adaptive_max_dimension = p->opts->max_dimension == 0
        ? effective_max_dimension(p) : 0;
    p->sws = mp_sws_alloc(p);
    MP_HANDLE_OOM(p->sws);
    mp_filter_add_pin(f, MP_PIN_IN, "in");
    mp_filter_add_pin(f, MP_PIN_OUT, "out");
    p->engine = mpvflow_create(p->opts->block_size, p->opts->search_radius);
    p->active = p->engine != NULL;
    MP_INFO(f, "MPVFLOW_DIAGNOSTIC event=initialized target_fps=%.0f max_dimension=%d effective_max_dimension=%d block=%d search_radius=%d engine_ready=%d\n",
            p->opts->target_fps, p->opts->max_dimension,
            effective_max_dimension(p), p->opts->block_size,
            p->opts->search_radius, p->active);
    return f;
}
const struct mp_user_filter_entry vf_mpvflow = {
    .desc = {
        .description = "On-device motion-estimation frame interpolation",
        .name = "mpvflow",
        .priv_size = sizeof(OPT_BASE_STRUCT),
        .priv_defaults = &f_opts_def,
        .options = f_opts_list,
    },
    .create = f_create,
};
