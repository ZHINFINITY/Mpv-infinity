/* MPVFLOW_RATE_LIMIT_HEADER */
#ifndef MPVFLOW_RATE_LIMIT_H
#define MPVFLOW_RATE_LIMIT_H

#include <math.h>
#include <stdbool.h>
#include <stdint.h>

#define MPVFLOW_RATE_LIMIT_ONE 1000000000ULL

struct mpvflow_rate_limiter {
    uint64_t last_submit_ns;
    uint64_t last_bucket;
    int target_fps;
    bool initialized;
};

/* A held sample belongs to one source pair. It may bridge redundant callbacks
 * within one target interval, but must never survive a seek or pair change. */
struct mpvflow_synthesis_identity {
    uint64_t pair_before;
    uint64_t pair_after;
    int64_t source_pts_before_ns;
    int64_t source_pts_after_ns;
    int64_t output_pts_ns;
    bool valid;
};

static inline uint64_t mpvflow_rate_limiter_interval_ns(int target_fps)
{
    if (target_fps <= 0)
        return 0;
    const uint64_t fps = (uint64_t) target_fps;
    return (MPVFLOW_RATE_LIMIT_ONE + fps - 1) / fps;
}

/* Invalid/unknown timestamps must disable reuse, not invoke an undefined
 * floating-point-to-integer conversion. The strict upper bound also excludes
 * INT64_MAX rounded upward by the double representation. */
static inline bool mpvflow_synthesis_timestamp_ns(double seconds, int64_t *result)
{
    const double ns = seconds * 1000000000.0;
    if (!isfinite(ns) || ns <= (double) INT64_MIN || ns >= (double) INT64_MAX)
        return false;
    *result = llround(ns);
    return true;
}

static inline bool mpvflow_synthesis_identity_matches(
    const struct mpvflow_synthesis_identity *cached,
    uint64_t pair_before, uint64_t pair_after,
    int64_t source_pts_before_ns, int64_t source_pts_after_ns,
    int64_t output_pts_ns, int target_fps)
{
    const uint64_t interval = mpvflow_rate_limiter_interval_ns(target_fps);
    if (!interval || !cached->valid || cached->pair_before != pair_before ||
        cached->pair_after != pair_after ||
        cached->source_pts_before_ns != source_pts_before_ns ||
        cached->source_pts_after_ns != source_pts_after_ns ||
        cached->output_pts_ns <= source_pts_before_ns ||
        cached->output_pts_ns >= source_pts_after_ns ||
        output_pts_ns < cached->output_pts_ns || output_pts_ns >= source_pts_after_ns)
        return false;
    /* Unsigned subtraction avoids signed overflow for extreme, ordered PTS. */
    return (uint64_t) output_pts_ns - (uint64_t) cached->output_pts_ns < interval;
}

/* Source PTS may repeat across output redraws. Prefer the monotonic render callback clock. */
static inline uint64_t mpvflow_rate_limiter_timestamp_ns(
    int64_t presentation_pts_ns, uint64_t render_start_ns)
{
    if (render_start_ns > 0)
        return render_start_ns;
    return presentation_pts_ns > 0 ? (uint64_t) presentation_pts_ns : 0;
}

static inline void mpvflow_rate_limiter_reset(struct mpvflow_rate_limiter *limiter)
{
    *limiter = (struct mpvflow_rate_limiter) {0};
}

/* Admission follows monotonic output time and never accumulates burst credit. */
static inline bool mpvflow_rate_limiter_ready(
    const struct mpvflow_rate_limiter *limiter, uint64_t timestamp_ns, int target_fps)
{
    if (target_fps <= 0 || !limiter->initialized)
        return true;
    if (timestamp_ns < limiter->last_submit_ns)
        return false;
    if (limiter->target_fps != target_fps)
        return true;
    /* The phase is the absolute monotonic clock origin, not the last submit.
     * Thus a late callback consumes only its current bucket and never creates
     * catch-up credit. Division also avoids timestamp + interval overflow. */
    return timestamp_ns / mpvflow_rate_limiter_interval_ns(target_fps) >
           limiter->last_bucket;
}

/* Commit only after successful presentation submission; ignore a backward clock sample. */
static inline void mpvflow_rate_limiter_commit(
    struct mpvflow_rate_limiter *limiter, uint64_t timestamp_ns, int target_fps)
{
    if (target_fps <= 0) {
        mpvflow_rate_limiter_reset(limiter);
        return;
    }
    if (limiter->initialized && timestamp_ns < limiter->last_submit_ns)
        return;
    limiter->initialized = true;
    limiter->last_submit_ns = timestamp_ns;
    limiter->target_fps = target_fps;
    limiter->last_bucket = timestamp_ns / mpvflow_rate_limiter_interval_ns(target_fps);
}

/* Skip new motion analysis only if the measured prior present wait exhausted this frame budget. */
static inline bool mpvflow_previous_present_budget_available(
    uint64_t present_wait_ns, int target_fps)
{
    const uint64_t interval_ns = mpvflow_rate_limiter_interval_ns(target_fps);
    if (!interval_ns)
        return true;
    return present_wait_ns < interval_ns;
}

#endif /* MPVFLOW_RATE_LIMIT_H */
