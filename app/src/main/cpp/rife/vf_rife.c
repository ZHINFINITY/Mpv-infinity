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
#define RIFE_LOG_EVERY 120
#define RIFE_FALLBACK_QUEUE_SIZE 4
#define RIFE_SLOW_INFERENCE_LIMIT 3
#define RIFE_SLOW_INFERENCE_BUDGET_MULTIPLIER 1.5

struct f_opts {
    char *model_dir;
    double target_fps;
};

#define OPT_BASE_STRUCT struct f_opts
static const struct m_option f_opts_list[] = {
    {"model-dir", OPT_STRING(model_dir)},
    {"target-fps", OPT_DOUBLE(target_fps), M_RANGE(1.0, 240.0)},
    {0}
};

static const struct f_opts f_opts_def = {
    .target_fps = 60.0,
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
    bool warned;
    bool passthrough_recovery_pending;
    bool performance_limited;
    unsigned int slow_inference_count;
    struct mp_frame fallback[RIFE_FALLBACK_QUEUE_SIZE];
    int fallback_count;
    int fallback_pos;

    int64_t inference_ns;
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

static bool image_is_supported(struct mp_image *img)
{
    if (!img || img->w <= 0 || img->h <= 0 ||
        (img->fields & MP_IMGFIELD_INTERLACED) || img->hwctx ||
        IMGFMT_IS_HWACCEL(img->imgfmt) ||
        img->params.color.transfer == PL_COLOR_TRC_PQ ||
        img->params.color.transfer == PL_COLOR_TRC_HLG)
        return false;

    // Unknown component descriptions are rejected rather than guessing depth.
    if (!(img->fmt.flags & MP_IMGFLAG_HAS_COMPS))
        return false;
    for (int n = 0; n < MP_NUM_COMPONENTS; n++) {
        if (img->fmt.comps[n].size > 8)
            return false;
    }
    return true;
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

static void log_fallback(struct mp_filter *f, const char *why)
{
    struct priv *p = f->priv;
    if (!p->warned) {
        MP_WARN(f, "RIFE_DIAGNOSTIC state=passthrough reason=%s\n", why);
        p->warned = true;
    }
}

// Keep original frames that have not yet appeared on the output timeline, then
// queue the held source frame(s) in decode order before switching to passthrough.
static void disable_and_queue(struct mp_filter *f, const char *why,
                              struct mp_frame current)
{
    struct priv *p = f->priv;
    MP_WARN(f, "RIFE_DIAGNOSTIC event=disabled reason=%s\n", why);
    p->warned = true;

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
static void bypass_unsupported_segment(struct mp_filter *f, const char *why)
{
    struct priv *p = f->priv;
    log_fallback(f, why);

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
    size_t width = (size_t)a->w;
    size_t height = (size_t)a->h;
    if (width > SIZE_MAX / 3 || height > SIZE_MAX / (width * 3))
        return NULL;
    size_t row_bytes = width * 3;
    size_t bytes = row_bytes * height;

    struct mp_image *rgb_a = mp_image_alloc(IMGFMT_RGB24, a->w, a->h);
    struct mp_image *rgb_b = mp_image_alloc(IMGFMT_RGB24, a->w, a->h);
    struct mp_image *rgb_out = mp_image_alloc(IMGFMT_RGB24, a->w, a->h);
    struct mp_image *out = NULL;
    uint8_t *packed_a = NULL, *packed_b = NULL, *packed_out = NULL;
    bool ok = false;

    if (!rgb_a || !rgb_b || !rgb_out)
        goto done;
    if (!convert_to_rgb(p, a, rgb_a) || !convert_to_rgb(p, b, rgb_b))
        goto done;

    packed_a = malloc(bytes);
    packed_b = malloc(bytes);
    packed_out = malloc(bytes);
    if (!packed_a || !packed_b || !packed_out ||
        !pack_rgb24(rgb_a, packed_a, row_bytes) ||
        !pack_rgb24(rgb_b, packed_b, row_bytes))
        goto done;

    double span = b->pts - a->pts;
    double alpha = (pts - a->pts) / span;
    if (!isfinite(alpha) || alpha < 0.0 || alpha > 1.0)
        goto done;

    int64_t start = mp_time_ns();
    int result = rife_vfi_interpolate_rgb24(p->engine, packed_a, packed_b,
                                            a->w, a->h, (float)alpha,
                                            packed_out);
    int64_t elapsed = mp_time_ns() - start;
    if (elapsed > 0)
        p->inference_ns += elapsed;
    p->inference_count++;
    int64_t inference_budget_ns =
        (int64_t)(p->frame_step * 1e9 * RIFE_SLOW_INFERENCE_BUDGET_MULTIPLIER);
    if (elapsed > inference_budget_ns)
        p->slow_inference_count++;
    else
        p->slow_inference_count = 0;
    if (p->slow_inference_count >= RIFE_SLOW_INFERENCE_LIMIT) {
        p->performance_limited = true;
        MP_WARN(f, "RIFE_DIAGNOSTIC event=auto_fallback reason=slow_inference target_fps=%.0f last_inference_ms=%.2f consecutive_slow=%u\n",
                p->opts->target_fps, elapsed / 1e6, p->slow_inference_count);
        goto done;
    }
    if (p->inference_count >= RIFE_LOG_EVERY) {
        MP_INFO(f, "RIFE_DIAGNOSTIC event=inference_latency average_frames=%u average_ms=%.2f target_fps=%.0f\n",
                p->inference_count,
                p->inference_ns / (double)p->inference_count / 1e6,
                p->opts->target_fps);
        p->inference_count = 0;
        p->inference_ns = 0;
    }
    if (result != 0 || !unpack_rgb24(packed_out, rgb_out, row_bytes))
        goto done;

    out = mp_image_alloc(a->imgfmt, a->w, a->h);
    if (!out)
        goto done;
    mp_image_copy_attributes(out, a);
    out->pts = pts;
    mp_image_copy_attributes(rgb_out, a);
    if (mp_sws_scale(p->sws, out, rgb_out) < 0) {
        talloc_free(out);
        out = NULL;
        goto done;
    }
    // swscale uses image plane strides; attributes and the synthesized PTS stay source-derived.
    out->pts = pts;
    ok = true;

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
            if (!img || !image_is_supported(img) || !pts_is_valid(img->pts)) {
                const char *why = !img ? "non-video frame" :
                    !image_is_supported(img) ? "unsupported frame format" :
                    "missing PTS";
                log_fallback(f, why);
                p->passthrough_recovery_pending = true;
                mp_pin_in_write(f->ppins[1], frame);
                return;
            }
            if (p->passthrough_recovery_pending) {
                MP_INFO(f, "RIFE_DIAGNOSTIC state=interpolating resumed=1 target_fps=%.0f\n",
                        p->opts->target_fps);
                p->passthrough_recovery_pending = false;
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
        if (!b || !frames_compatible(a, b) || !pts_is_valid(b->pts) ||
            b->pts <= a->pts) {
            const char *why = !b ? "non-video frame" :
                !image_is_supported(b) ? "unsupported frame format" :
                !pts_is_valid(b->pts) ? "missing PTS" :
                b->pts <= a->pts ? "non-monotonic PTS" :
                "incompatible frame format or dimensions";
            bypass_unsupported_segment(f, why);
            write_fallback(f);
            return;
        }

        double tolerance = fmax(1e-7, p->frame_step * 1e-6);
        double span = b->pts - p->next_pts;
        if (p->next_pts < b->pts - tolerance) {
            // Cap work for a source gap before emitting any outputs from that gap.
            double count = ceil((span - tolerance) / p->frame_step);
            if (!isfinite(count) || count > RIFE_MAX_SYNTH_PER_GAP) {
                disable_and_queue(f, "source timestamp gap exceeds the synthesis limit",
                                  MP_NO_FRAME);
                write_fallback(f);
                return;
            }
            struct mp_image *out = interpolate(f, a, b, p->next_pts);
            if (!out) {
                const char *why = p->performance_limited ?
                    "inference exceeded target frame-time budget" :
                    "swscale conversion or RIFE inference failed";
                p->performance_limited = false;
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
    p->warned = false;
    p->passthrough_recovery_pending = false;
    p->performance_limited = false;
    p->slow_inference_count = 0;
    p->inference_ns = 0;
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
    p->engine = rife_vfi_create(p->opts->model_dir, error, sizeof(error));
    p->active = p->engine != NULL;
    MP_INFO(f, "RIFE_DIAGNOSTIC event=initialized target_fps=%.0f engine_ready=%d\n",
            p->opts->target_fps, p->active);
    if (!p->engine) {
        MP_WARN(f, "RIFE model initialization failed%s%s; passing source frames through.\n",
                error[0] ? ": " : "", error);
        p->warned = true;
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
