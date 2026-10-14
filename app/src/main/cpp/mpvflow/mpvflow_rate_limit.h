/* MPVFLOW_RATE_LIMIT_HEADER */
#ifndef MPVFLOW_RATE_LIMIT_H
#define MPVFLOW_RATE_LIMIT_H

#include <stdbool.h>
#include <stdint.h>

#define MPVFLOW_RATE_LIMIT_ONE 1000000000ULL

struct mpvflow_rate_limiter {
    uint64_t last_submit_ns;
    bool initialized;
};

/* MPV vo_frame.pts is the scheduled show time in mp_time_ns units. */
static inline uint64_t mpvflow_rate_limiter_timestamp_ns(
    const struct mpvflow_rate_limiter *limiter, int64_t presentation_pts_ns,
    uint64_t render_start_ns)
{
    if (presentation_pts_ns <= 0)
        return render_start_ns;
    const uint64_t presentation_ns = (uint64_t) presentation_pts_ns;
    if (limiter && limiter->initialized && presentation_ns < limiter->last_submit_ns)
        return render_start_ns;
    return presentation_ns;
}

static inline void mpvflow_rate_limiter_reset(struct mpvflow_rate_limiter *limiter)
{
    *limiter = (struct mpvflow_rate_limiter) {0};
}

static inline uint64_t mpvflow_rate_limiter_interval_ns(int target_fps)
{
    if (target_fps <= 0)
        return 0;
    const uint64_t fps = (uint64_t) target_fps;
    return (MPVFLOW_RATE_LIMIT_ONE + fps - 1) / fps;
}

/* Admission follows scheduled presentation timestamps when available, never burst credit. */
static inline bool mpvflow_rate_limiter_ready(
    const struct mpvflow_rate_limiter *limiter, uint64_t timestamp_ns, int target_fps)
{
    if (target_fps <= 0 || !limiter->initialized)
        return true;
    if (timestamp_ns < limiter->last_submit_ns)
        return false;
    return timestamp_ns - limiter->last_submit_ns >=
           mpvflow_rate_limiter_interval_ns(target_fps);
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
