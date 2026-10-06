/*
 * This file is part of mpv.
 *
 * mpv is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

#include <math.h>
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

#include "rife_vfi.h"

#define RIFE_MAX_SYNTH_PER_GAP 12
#define RIFE_LOG_EVERY 60
#define RIFE_FALLBACK_QUEUE_SIZE 4
#define RIFE_SLOW_INFERENCE_LIMIT 2
#define RIFE_SLOW_INFERENCE_BUDGET_MULTIPLIER 1.5
#define RIFE_SEVERE_INFERENCE_BUDGET_MULTIPLIER 3.0

struct f_opts {
    char *model_dir;
    double target_fps;
    int max_dimension;
};

#define OPT_BASE_STRUCT struct f_opts
static const struct m_option f_opts_list[] = {
    {"model-dir", OPT_STRING(model_dir)},
    {"target-fps", OPT_DOUBLE(target_fps), M_RANGE(1.0, 240.0)},
    {"max-dimension", OPT_INT(max_dimension), M_RANGE(-1, 8192)},
    {0}
};

static const struct f_opts f_opts_def = {
    .target_fps = 60.0,
    .max_dimension = 0,
};

struct priv {
    struct f_opts *opts;
    RifeVfiEngine *engine;
    struct mp_sws_context *sws;

    // prev and pending are the adjacent decoded frames bracketing output times.
    struct mp_frame prev;
    struct mp_frame pending;
    bool prev_was_emitted;
    double next_pts;
    double frame_step;
    unsigned int outputs_in_gap;

    bool active;
    bool passthrough_recovery_pending;
    bool performance_limited;
    const char *performance_limited_reason;
    unsigned int slow_inference_count;
    unsigned int slow_total_count;
    char last_passthrough_reason[64];
    int last_passthrough_format;
    int last_passthrough_width;
    int last_passthrough_height;
    int last_passthrough_transfer;
    unsigned int passthrough_run_count;
    int last_input_format;
    int last_input_width;
    int last_input_height;
    int last_input_depth;
    int last_input_transfer;
    int last_process_width;
    int last_process_height;
    struct mp_frame fallback[RIFE_FALLBACK_QUEUE_SIZE];
    int fallback_count;
    int fallback_pos;

    int64_t inference_ns;
    int64_t conversion_ns;
    int64_t output_ns;
    int64_t total_ns;
    unsigned int inference_count;
};

static struct mp_image *frame_image(struct mp_frame frame)
{
    return frame.type == MP_FRAME_VIDEO ? frame.data : NULL;
}

static bool pts_is_valid(double pts)
{
    return isfinite(pts) && pts != MP_NOPTS_VALUE;
}

static int image_component_depth(struct mp_image *img)
{
    if (!img || !(img->fmt.flags & MP_IMGFLAG_HAS_COMPS))
        return 0;
    int depth = 0;
    for (int n = 0; n < MP_NUM_COMPONENTS; n++)
        if (img->fmt.comps[n].size > depth)
            depth = img->fmt.comps[n].size;
    return depth;
}

static const char *image_unsupported_reason(struct mp_image *img)
{
    if (!img)
        return "non_video_frame";
    if (img->w <= 0 || img->h <= 0)
        return "invalid_dimensions";
    if (img->fields & MP_IMGFIELD_INTERLACED)
        return "interlaced";
    if (img->hwctx || IMGFMT_IS_HWACCEL(img->imgfmt))
        return "hardware_only_frame";
    if (img->params.color.transfer == PL_COLOR_TRC_PQ)
        return "hdr_pq";
    if (img->params.color.transfer == PL_COLOR_TRC_HLG)
        return "hdr_hlg";

    // Unknown component descriptions are rejected rather than guessing depth.
    if (!(img->fmt.flags & MP_IMGFLAG_HAS_COMPS))
        return "unknown_pixel_format";

    // SDR 10/12/16-bit frames are safe to feed through swscale's explicit RGB24
    // conversion. HDR transfer functions remain pass-through above.
    return NULL;
}

static bool image_is_supported(struct mp_image *img)
{
    return image_unsupported_reason(img) == NULL;
}

static int effective_max_dimension(struct priv *p)
{
    if (p->opts->max_dimension < 0)
        return 0; // Source resolution (no cap).
    if (p->opts->max_dimension > 0)
        return p->opts->max_dimension;
    if (p->opts->target_fps >= 60.0)
        return 480;
    if (p->opts->target_fps >= 48.0)
        return 720;
    return 1080;
}

static void processing_dimensions(struct priv *p, int width, int height,
                                  int *process_width, int *process_height)
{
    int limit = effective_max_dimension(p);
    int longest = width > height ? width : height;
    double scale = limit > 0 && longest > limit ? limit / (double)longest : 1.0;
    *process_width = (int)lround(width * scale);
    *process_height = (int)lround(height * scale);
    if (*process_width < 1)
        *process_width = 1;
    if (*process_height < 1)
        *process_height = 1;
}

static void log_input_path(struct mp_filter *f, struct mp_image *img)
{
    struct priv *p = f->priv;
    int process_width, process_height;
    processing_dimensions(p, img->w, img->h, &process_width, &process_height);
    int depth = image_component_depth(img);
    int transfer = (int)img->params.color.transfer;
    if (p->last_input_format == img->imgfmt &&
        p->last_input_width == img->w && p->last_input_height == img->h &&
        p->last_input_depth == depth && p->last_input_transfer == transfer &&
        p->last_process_width == process_width &&
        p->last_process_height == process_height)
        return;
    MP_INFO(f, "RIFE_DIAGNOSTIC event=input format=%s width=%d height=%d component_bits=%d transfer=%d interlaced=%d hardware=%d process_width=%d process_height=%d effective_max_dimension=%d target_fps=%.0f\n",
            mp_imgfmt_to_name(img->imgfmt), img->w, img->h, depth, transfer,
            !!(img->fields & MP_IMGFIELD_INTERLACED),
            !!(img->hwctx || IMGFMT_IS_HWACCEL(img->imgfmt)),
            process_width, process_height, effective_max_dimension(p),
            p->opts->target_fps);
    p->last_input_format = img->imgfmt;
    p->last_input_width = img->w;
    p->last_input_height = img->h;
    p->last_input_depth = depth;
    p->last_input_transfer = transfer;
    p->last_process_width = process_width;
    p->last_process_height = process_height;
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
}

static void fallback_add(struct priv *p, struct mp_frame frame)
{
    if (!frame.type || p->fallback_count >= RIFE_FALLBACK_QUEUE_SIZE) {
        mp_frame_unref(&frame);
        return;
    }
    p->fallback[p->fallback_count++] = frame;
}

static void log_fallback(struct mp_filter *f, struct mp_image *img,
                         const char *why)
{
    struct priv *p = f->priv;
    int format = img ? img->imgfmt : 0;
    int width = img ? img->w : 0;
    int height = img ? img->h : 0;
    int transfer = img ? (int)img->params.color.transfer : -1;
    bool repeated = p->last_passthrough_format == format &&
        p->last_passthrough_width == width &&
        p->last_passthrough_height == height &&
        p->last_passthrough_transfer == transfer &&
        strcmp(p->last_passthrough_reason, why) == 0;
    if (repeated) {
        p->passthrough_run_count++;
        if (p->passthrough_run_count % RIFE_LOG_EVERY == 0) {
            MP_WARN(f, "RIFE_DIAGNOSTIC event=passthrough_summary reason=%s format=%s width=%d height=%d repeated_frames=%u\n",
                    why, img ? mp_imgfmt_to_name(img->imgfmt) : "none",
                    width, height, p->passthrough_run_count);
        }
        return;
    }

    snprintf(p->last_passthrough_reason, sizeof(p->last_passthrough_reason),
             "%s", why);
    p->last_passthrough_format = format;
    p->last_passthrough_width = width;
    p->last_passthrough_height = height;
    p->last_passthrough_transfer = transfer;
    p->passthrough_run_count = 1;
    MP_WARN(f, "RIFE_DIAGNOSTIC event=passthrough reason=%s format=%s width=%d height=%d component_bits=%d transfer=%d interlaced=%d hardware=%d pts_valid=%d pts=%.6f\n",
            why, img ? mp_imgfmt_to_name(img->imgfmt) : "none", width, height,
            image_component_depth(img), transfer,
            img ? !!(img->fields & MP_IMGFIELD_INTERLACED) : 0,
            img ? !!(img->hwctx || IMGFMT_IS_HWACCEL(img->imgfmt)) : 0,
            img ? pts_is_valid(img->pts) : 0, img ? img->pts : 0.0);
}

// Keep original frames that have not yet appeared on the output timeline, then
// queue the held source frame(s) in decode order before switching to passthrough.
static void disable_and_queue(struct mp_filter *f, const char *why,
                              struct mp_frame current)
{
    struct priv *p = f->priv;
    MP_WARN(f, "RIFE_DIAGNOSTIC event=disabled reason=%s target_fps=%.0f effective_max_dimension=%d slow_inference_count=%u slow_total_count=%u\n",
            why, p->opts->target_fps, effective_max_dimension(p),
            p->slow_inference_count, p->slow_total_count);

    if (p->prev.type && !p->prev_was_emitted && p->outputs_in_gap == 0)
        fallback_add(p, mp_frame_ref(p->prev));
    if (p->pending.type) {
        fallback_add(p, p->pending);
        p->pending = MP_NO_FRAME;
    }
    if (current.type)
        fallback_add(p, current);

    mp_frame_unref(&p->prev);
    p->active = false;
    p->outputs_in_gap = 0;
}

// A bad timestamp or one unsupported frame should not disable interpolation for the rest of the
// file. Flush any held source frames in order, then let the next valid frame pair re-arm RIFE.
static void bypass_unsupported_segment(struct mp_filter *f, struct mp_image *img,
                                       const char *why)
{
    struct priv *p = f->priv;
    log_fallback(f, img, why);

    if (p->prev.type && !p->prev_was_emitted && p->outputs_in_gap == 0)
        fallback_add(p, mp_frame_ref(p->prev));
    if (p->pending.type) {
        fallback_add(p, p->pending);
        p->pending = MP_NO_FRAME;
    }

    mp_frame_unref(&p->prev);
    p->prev = MP_NO_FRAME;
    p->next_pts = 0;
    p->prev_was_emitted = false;
    p->outputs_in_gap = 0;
    p->passthrough_recovery_pending = true;
}

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

static struct mp_image *interpolate(struct mp_filter *f, struct mp_image *a,
                                    struct mp_image *b, double pts)
{
    struct priv *p = f->priv;
    int process_width, process_height;
    processing_dimensions(p, a->w, a->h, &process_width, &process_height);
    size_t width = (size_t)process_width;
    size_t height = (size_t)process_height;
    if (width > SIZE_MAX / 3 || height > SIZE_MAX / (width * 3))
        return NULL;
    size_t row_bytes = width * 3;
    size_t bytes = row_bytes * height;
    int64_t total_start = mp_time_ns();

    struct mp_image *rgb_a = mp_image_alloc(IMGFMT_RGB24, process_width, process_height);
    struct mp_image *rgb_b = mp_image_alloc(IMGFMT_RGB24, process_width, process_height);
    struct mp_image *rgb_out = mp_image_alloc(IMGFMT_RGB24, process_width, process_height);
    struct mp_image *out = NULL;
    uint8_t *packed_a = NULL, *packed_b = NULL, *packed_out = NULL;
    bool ok = false;

    if (!rgb_a || !rgb_b || !rgb_out) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=processing_error reason=rgb_allocation_failed source_width=%d source_height=%d process_width=%d process_height=%d\n",
                a->w, a->h, process_width, process_height);
        goto done;
    }
    int64_t conversion_start = mp_time_ns();
    if (!convert_to_rgb(p, a, rgb_a) || !convert_to_rgb(p, b, rgb_b)) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=processing_error reason=source_rgb_conversion_failed source_format=%s source_width=%d source_height=%d process_width=%d process_height=%d component_bits=%d\n",
                mp_imgfmt_to_name(a->imgfmt), a->w, a->h, process_width,
                process_height, image_component_depth(a));
        goto done;
    }

    packed_a = malloc(bytes);
    packed_b = malloc(bytes);
    packed_out = malloc(bytes);
    if (!packed_a || !packed_b || !packed_out ||
        !pack_rgb24(rgb_a, packed_a, row_bytes) ||
        !pack_rgb24(rgb_b, packed_b, row_bytes)) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=processing_error reason=rgb_pack_failed process_width=%d process_height=%d bytes=%zu\n",
                process_width, process_height, bytes);
        goto done;
    }
    int64_t conversion_elapsed = mp_time_ns() - conversion_start;
    if (conversion_elapsed > 0)
        p->conversion_ns += conversion_elapsed;

    double span = b->pts - a->pts;
    double alpha = (pts - a->pts) / span;
    if (!isfinite(alpha) || alpha < 0.0 || alpha > 1.0) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=processing_error reason=invalid_timestep source_delta_ms=%.3f alpha=%.6f\n",
                span * 1000.0, alpha);
        goto done;
    }

    int64_t start = mp_time_ns();
    int result = rife_vfi_interpolate_rgb24(p->engine, packed_a, packed_b,
                                            process_width, process_height,
                                            (float)alpha,
                                            packed_out);
    int64_t elapsed = mp_time_ns() - start;
    if (elapsed > 0)
        p->inference_ns += elapsed;
    p->inference_count++;
    int64_t frame_period_ns = (int64_t)(p->frame_step * 1e9);
    int64_t inference_budget_ns =
        (int64_t)(frame_period_ns * RIFE_SLOW_INFERENCE_BUDGET_MULTIPLIER);
    int64_t severe_inference_budget_ns =
        (int64_t)(frame_period_ns * RIFE_SEVERE_INFERENCE_BUDGET_MULTIPLIER);
    bool severe_slowdown = elapsed > severe_inference_budget_ns;
    if (elapsed > inference_budget_ns)
        p->slow_inference_count++;
    else
        p->slow_inference_count = 0;
    if (severe_slowdown || p->slow_inference_count >= RIFE_SLOW_INFERENCE_LIMIT) {
        p->performance_limited = true;
        p->performance_limited_reason = "inference_exceeded_target_frame_time_budget";
        MP_WARN(f, "RIFE_DIAGNOSTIC event=auto_fallback reason=slow_inference target_fps=%.0f last_inference_ms=%.2f frame_budget_ms=%.2f source_format=%s source_width=%d source_height=%d process_width=%d process_height=%d component_bits=%d source_delta_ms=%.3f alpha=%.4f consecutive_slow=%u immediate=%d\n",
                p->opts->target_fps, elapsed / 1e6, frame_period_ns / 1e6,
                mp_imgfmt_to_name(a->imgfmt), a->w, a->h, process_width,
                process_height, image_component_depth(a), span * 1000.0, alpha,
                p->slow_inference_count, severe_slowdown ? 1 : 0);
        goto done;
    }
    if (result != 0) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=processing_error reason=ncnn_inference_failed result=%d process_width=%d process_height=%d\n",
                result, process_width, process_height);
        goto done;
    }
    if (!unpack_rgb24(packed_out, rgb_out, row_bytes)) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=processing_error reason=rgb_unpack_failed process_width=%d process_height=%d\n",
                process_width, process_height);
        goto done;
    }

    out = mp_image_alloc(a->imgfmt, a->w, a->h);
    if (!out) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=processing_error reason=output_allocation_failed source_format=%s source_width=%d source_height=%d\n",
                mp_imgfmt_to_name(a->imgfmt), a->w, a->h);
        goto done;
    }
    mp_image_copy_attributes(out, a);
    out->pts = pts;
    mp_image_copy_attributes(rgb_out, a);
    if (mp_sws_scale(p->sws, out, rgb_out) < 0) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=processing_error reason=synthesized_output_conversion_failed source_format=%s source_width=%d source_height=%d process_width=%d process_height=%d\n",
                mp_imgfmt_to_name(a->imgfmt), a->w, a->h,
                process_width, process_height);
        talloc_free(out);
        out = NULL;
        goto done;
    }
    // swscale uses image plane strides; attributes and the synthesized PTS stay source-derived.
    out->pts = pts;
    int64_t output_elapsed = mp_time_ns() - start - elapsed;
    if (output_elapsed > 0)
        p->output_ns += output_elapsed;
    int64_t total_elapsed = mp_time_ns() - total_start;
    if (total_elapsed > 0)
        p->total_ns += total_elapsed;
    bool severe_total_slowdown = total_elapsed > severe_inference_budget_ns;
    if (total_elapsed > inference_budget_ns)
        p->slow_total_count++;
    else
        p->slow_total_count = 0;
    if (severe_total_slowdown || p->slow_total_count >= RIFE_SLOW_INFERENCE_LIMIT) {
        p->performance_limited = true;
        p->performance_limited_reason = "total_processing_exceeded_target_frame_time_budget";
        MP_WARN(f, "RIFE_DIAGNOSTIC event=auto_fallback reason=slow_total_processing target_fps=%.0f frame_budget_ms=%.2f convert_ms=%.2f inference_ms=%.2f output_ms=%.2f total_ms=%.2f source_format=%s source_width=%d source_height=%d process_width=%d process_height=%d component_bits=%d consecutive_slow=%u immediate=%d\n",
                p->opts->target_fps, frame_period_ns / 1e6,
                conversion_elapsed / 1e6, elapsed / 1e6, output_elapsed / 1e6,
                total_elapsed / 1e6, mp_imgfmt_to_name(a->imgfmt), a->w, a->h,
                process_width, process_height, image_component_depth(a),
                p->slow_total_count, severe_total_slowdown ? 1 : 0);
        talloc_free(out);
        out = NULL;
        goto done;
    }
    ok = true;
    if (p->inference_count >= RIFE_LOG_EVERY) {
        MP_INFO(f, "RIFE_DIAGNOSTIC event=frame_timing frames=%u source_format=%s source_width=%d source_height=%d process_width=%d process_height=%d average_convert_ms=%.2f average_inference_ms=%.2f average_output_ms=%.2f average_total_ms=%.2f source_delta_ms=%.3f target_fps=%.0f\n",
                p->inference_count, mp_imgfmt_to_name(a->imgfmt), a->w, a->h,
                process_width, process_height,
                p->conversion_ns / (double)p->inference_count / 1e6,
                p->inference_ns / (double)p->inference_count / 1e6,
                p->output_ns / (double)p->inference_count / 1e6,
                p->total_ns / (double)p->inference_count / 1e6,
                span * 1000.0, p->opts->target_fps);
        p->inference_count = 0;
        p->inference_ns = 0;
        p->conversion_ns = 0;
        p->output_ns = 0;
        p->total_ns = 0;
    }

done:
    free(packed_a);
    free(packed_b);
    free(packed_out);
    talloc_free(rgb_a);
    talloc_free(rgb_b);
    talloc_free(rgb_out);
    if (!ok && out) {
        talloc_free(out);
        out = NULL;
    }
    return out;
}

static void promote_pending(struct priv *p, bool emitted)
{
    mp_frame_unref(&p->prev);
    p->prev = mp_frame_ref(p->pending);
    mp_frame_unref(&p->pending);
    p->prev_was_emitted = emitted;
    p->outputs_in_gap = 0;
}

static bool frames_compatible(struct mp_image *a, struct mp_image *b)
{
    return image_is_supported(a) && image_is_supported(b) &&
           a->w == b->w && a->h == b->h && a->imgfmt == b->imgfmt;
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
            const char *unsupported_reason = image_unsupported_reason(img);
            if (unsupported_reason || !pts_is_valid(img->pts)) {
                const char *why = unsupported_reason ? unsupported_reason : "missing_pts";
                log_fallback(f, img, why);
                p->passthrough_recovery_pending = true;
                mp_pin_in_write(f->ppins[1], frame);
                return;
            }
            log_input_path(f, img);
            if (p->passthrough_recovery_pending) {
                MP_INFO(f, "RIFE_DIAGNOSTIC event=interpolating resumed=1 target_fps=%.0f format=%s width=%d height=%d component_bits=%d\n",
                        p->opts->target_fps, mp_imgfmt_to_name(img->imgfmt),
                        img->w, img->h, image_component_depth(img));
                p->passthrough_recovery_pending = false;
                p->last_passthrough_reason[0] = '\0';
                p->passthrough_run_count = 0;
            }
            p->prev = mp_frame_ref(frame);
            p->prev_was_emitted = true;
            p->next_pts = img->pts;
            mp_pin_in_write(f->ppins[1], frame); // Preserve the first source frame unchanged.
            p->next_pts += p->frame_step;
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
                mp_pin_in_write(f->ppins[1], frame);
                return;
            }
            p->pending = frame;
        }

        struct mp_image *a = frame_image(p->prev);
        struct mp_image *b = frame_image(p->pending);
        const char *unsupported_reason = image_unsupported_reason(b);
        if (unsupported_reason || !frames_compatible(a, b) ||
            !pts_is_valid(b->pts) || b->pts <= a->pts) {
            const char *why = unsupported_reason ? unsupported_reason :
                !pts_is_valid(b->pts) ? "missing_pts" :
                b->pts <= a->pts ? "non_monotonic_pts" :
                a->imgfmt != b->imgfmt ? "incompatible_pixel_format" :
                "incompatible_dimensions";
            bypass_unsupported_segment(f, b, why);
            write_fallback(f);
            return;
        }

        double tolerance = fmax(1e-7, p->frame_step * 1e-6);
        double span = b->pts - p->next_pts;
        if (p->next_pts < b->pts - tolerance) {
            // Cap work for a source gap before emitting any outputs from that gap.
            double count = ceil((span - tolerance) / p->frame_step);
            if (!isfinite(count) || count > RIFE_MAX_SYNTH_PER_GAP) {
                disable_and_queue(f, "source_timestamp_gap_exceeds_synthesis_limit",
                                  MP_NO_FRAME);
                write_fallback(f);
                return;
            }
            struct mp_image *out = interpolate(f, a, b, p->next_pts);
            if (!out) {
                const char *why = p->performance_limited && p->performance_limited_reason ?
                    p->performance_limited_reason : "conversion_or_rife_inference_failed";
                p->performance_limited = false;
                p->performance_limited_reason = NULL;
                disable_and_queue(f, why,
                                  MP_NO_FRAME);
                write_fallback(f);
                return;
            }
            struct mp_frame output = MAKE_FRAME(MP_FRAME_VIDEO, out);
            p->next_pts += p->frame_step;
            p->outputs_in_gap++;
            mp_pin_in_write(f->ppins[1], output);
            return;
        }

        if (fabs(p->next_pts - b->pts) <= tolerance) {
            struct mp_frame output = p->pending;
            p->pending = MP_NO_FRAME;
            mp_frame_unref(&p->prev);
            p->prev = mp_frame_ref(output);
            p->prev_was_emitted = true;
            p->next_pts += p->frame_step;
            p->outputs_in_gap = 0;
            mp_pin_in_write(f->ppins[1], output);
            return;
        }

        // This decoded frame brackets no target time; retain it as the next input.
        promote_pending(p, false);
    }
}

static void f_reset(struct mp_filter *f)
{
    struct priv *p = f->priv;
    clear_frames(p);
    p->active = p->engine != NULL;
    p->passthrough_recovery_pending = false;
    p->performance_limited = false;
    p->performance_limited_reason = NULL;
    p->slow_inference_count = 0;
    p->slow_total_count = 0;
    p->last_passthrough_reason[0] = '\0';
    p->last_passthrough_format = 0;
    p->last_passthrough_width = 0;
    p->last_passthrough_height = 0;
    p->last_passthrough_transfer = 0;
    p->passthrough_run_count = 0;
    p->last_input_format = 0;
    p->last_input_width = 0;
    p->last_input_height = 0;
    p->last_input_depth = 0;
    p->last_input_transfer = 0;
    p->last_process_width = 0;
    p->last_process_height = 0;
    p->inference_ns = 0;
    p->conversion_ns = 0;
    p->output_ns = 0;
    p->total_ns = 0;
    p->inference_count = 0;
}

static void f_destroy(struct mp_filter *f)
{
    struct priv *p = f->priv;
    clear_frames(p);
    if (p->engine)
        rife_vfi_destroy(p->engine);
    p->engine = NULL;
}

static const struct mp_filter_info filter = {
    .name = "rife",
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
    p->sws = mp_sws_alloc(p);
    MP_HANDLE_OOM(p->sws);
    mp_filter_add_pin(f, MP_PIN_IN, "in");
    mp_filter_add_pin(f, MP_PIN_OUT, "out");

    char error[256] = {0};
    int64_t initialization_start = mp_time_ns();
    p->engine = rife_vfi_create(p->opts->model_dir, error, sizeof(error));
    p->active = p->engine != NULL;
    int64_t initialization_ns = mp_time_ns() - initialization_start;
    MP_INFO(f, "RIFE_DIAGNOSTIC event=initialized target_fps=%.0f resolution_setting=%d effective_max_dimension=%d engine_ready=%d model_load_ms=%.2f\n",
            p->opts->target_fps, p->opts->max_dimension,
            effective_max_dimension(p), p->active, initialization_ns / 1e6);
    if (!p->engine) {
        MP_WARN(f, "RIFE_DIAGNOSTIC event=engine_error reason=initialization_failed error=%s\n",
                error[0] ? error : "unknown");
    }
    return f;
}

const struct mp_user_filter_entry vf_rife = {
    .desc = {
        .description = "Interpolate video frames with RIFE",
        .name = "rife",
        .priv_size = sizeof(OPT_BASE_STRUCT),
        .priv_defaults = &f_opts_def,
        .options = f_opts_list,
    },
    .create = f_create,
};
