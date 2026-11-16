#define _POSIX_C_SOURCE 200809L
#include "mpvrx_jelly_probe_writer.h"

#include <errno.h>
#include <math.h>
#include <stdarg.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define PW_GATE_CLOSED UINT32_C(0x80000000)
#define PW_GATE_ACTIVE_MASK UINT32_C(0x7fffffff)

_Static_assert(ATOMIC_INT_LOCK_FREE == 2,
               "probe callback atomics must be always lock-free");
_Static_assert(sizeof(float) == 4,
               "probe SSBO records require 32-bit floats");

int mpvrx_jelly_probe_record_is_activation(const void *mapped_record)
{
    if (!mapped_record)
        return 0;
    float reason;
    memcpy(&reason, (const unsigned char *) mapped_record + sizeof(float),
           sizeof(reason));
    return isfinite(reason) && reason == 6.0f;
}

int mpvrx_jelly_probe_record_was_evaluated(const void *mapped_record)
{
    if (!mapped_record)
        return 0;
    float marker;
    memcpy(&marker, mapped_record, sizeof(marker));
    return isfinite(marker) && marker == 1.0f;
}

static void diagnostics_reset(struct mpvrx_jelly_probe_diagnostics *d)
{
    memset(d, 0, sizeof(*d));
    d->setup_status = MPVRX_JELLY_STAGE_UNKNOWN;
    d->setup_error = -1;
    d->writer_init_status = MPVRX_JELLY_STAGE_UNKNOWN;
    d->writer_init_error = -1;
    d->buffer_status = MPVRX_JELLY_STAGE_UNKNOWN;
    d->buffer_error = -1;
    d->pixel_x = 1352;
    d->pixel_y = 224;
    d->source_width = d->source_height = -1;
    d->pixel_in_bounds = -1;
    d->last_pts_valid = -1;
    d->last_selection_mode = -1;
    d->gpu_last_poll_status = MPVRX_JELLY_READBACK_UNKNOWN;
    d->pixel_evaluated = -1;
    d->jelly_reason_code = -1;
    d->activation = -1;
    d->jelly_fill = NAN;
}

static void queue_signal(struct mpvrx_jelly_probe_queue *queue)
{
    if (sem_post(&queue->wake) != 0 && errno != EOVERFLOW)
        atomic_store_explicit(&queue->notify_error, errno, memory_order_relaxed);
}

static void queue_producer_leave(struct mpvrx_jelly_probe_queue *queue)
{
    uint32_t previous = atomic_fetch_sub_explicit(&queue->gate, 1,
                                                   memory_order_release);
    if ((previous & PW_GATE_CLOSED) &&
        (previous & PW_GATE_ACTIVE_MASK) == 1)
        queue_signal(queue);
}

int mpvrx_jelly_probe_queue_init(struct mpvrx_jelly_probe_queue *queue)
{
    if (!queue)
        return EINVAL;
    memset(queue, 0, sizeof(*queue));
    atomic_init(&queue->gate, 0);
    atomic_init(&queue->head, 0);
    atomic_init(&queue->tail, 0);
    atomic_init(&queue->notify_error, 0);
    if (sem_init(&queue->wake, 0, 0) != 0)
        return errno;
    queue->initialized = 1;
    return 0;
}

enum mpvrx_jelly_probe_queue_result mpvrx_jelly_probe_queue_try_push(
    struct mpvrx_jelly_probe_queue *queue,
    const unsigned char record[MPVRX_JELLY_PROBE_RECORD_BYTES])
{
    if (!queue || !queue->initialized || !record)
        return MPVRX_JELLY_QUEUE_INVALID;

    /* fetch_add is constant-work. SPSC means at most one active producer. */
    uint32_t prior = atomic_fetch_add_explicit(&queue->gate, 1,
                                               memory_order_acq_rel);
    if (prior & PW_GATE_CLOSED) {
        queue_producer_leave(queue);
        return MPVRX_JELLY_QUEUE_CLOSED;
    }
    if (prior & PW_GATE_ACTIVE_MASK) {
        queue_producer_leave(queue);
        return MPVRX_JELLY_QUEUE_INVALID; /* violated the single-producer contract */
    }

    uint32_t head = atomic_load_explicit(&queue->head, memory_order_relaxed);
    uint32_t tail = atomic_load_explicit(&queue->tail, memory_order_acquire);
    if ((uint32_t)(head - tail) >= 1) {
        queue_producer_leave(queue);
        return MPVRX_JELLY_QUEUE_FULL;
    }

    memcpy(queue->slot, record, MPVRX_JELLY_PROBE_RECORD_BYTES);
    atomic_store_explicit(&queue->head, head + 1, memory_order_release);
    queue_signal(queue);
    queue_producer_leave(queue);
    return atomic_load_explicit(&queue->notify_error, memory_order_relaxed)
        ? MPVRX_JELLY_QUEUE_NOTIFY_ERROR : MPVRX_JELLY_QUEUE_OK;
}

enum mpvrx_jelly_probe_queue_result mpvrx_jelly_probe_queue_try_pop(
    struct mpvrx_jelly_probe_queue *queue,
    unsigned char record[MPVRX_JELLY_PROBE_RECORD_BYTES])
{
    if (!queue || !queue->initialized || !record)
        return MPVRX_JELLY_QUEUE_INVALID;
    uint32_t tail = atomic_load_explicit(&queue->tail, memory_order_relaxed);
    uint32_t head = atomic_load_explicit(&queue->head, memory_order_acquire);
    if (tail == head)
        return MPVRX_JELLY_QUEUE_EMPTY;
    memcpy(record, queue->slot, MPVRX_JELLY_PROBE_RECORD_BYTES);
    atomic_store_explicit(&queue->tail, tail + 1, memory_order_release);
    return MPVRX_JELLY_QUEUE_OK;
}

void mpvrx_jelly_probe_queue_close(struct mpvrx_jelly_probe_queue *queue)
{
    if (!queue || !queue->initialized)
        return;
    uint32_t previous = atomic_fetch_or_explicit(&queue->gate, PW_GATE_CLOSED,
                                                 memory_order_acq_rel);
    if (!(previous & PW_GATE_CLOSED))
        queue_signal(queue);
}

int mpvrx_jelly_probe_queue_is_closed_and_drained(const struct mpvrx_jelly_probe_queue *queue)
{
    if (!queue || !queue->initialized)
        return 1;
    uint32_t gate = atomic_load_explicit(&queue->gate, memory_order_acquire);
    uint32_t head = atomic_load_explicit(&queue->head, memory_order_acquire);
    uint32_t tail = atomic_load_explicit(&queue->tail, memory_order_acquire);
    return (gate & PW_GATE_CLOSED) && !(gate & PW_GATE_ACTIVE_MASK) && head == tail;
}

void mpvrx_jelly_probe_queue_destroy(struct mpvrx_jelly_probe_queue *queue)
{
    if (!queue || !queue->initialized)
        return;
    /* Caller must have stopped and joined the consumer and quiesced producer. */
    sem_destroy(&queue->wake);
    queue->initialized = 0;
}

static void pw_set_terminal_status(struct mpvrx_jelly_probe_writer *writer, enum mpvrx_jelly_probe_writer_status status)
{
    int current = atomic_load_explicit(&writer->status, memory_order_acquire);
    while (current == MPVRX_JELLY_PROBE_CLAIMED || current == MPVRX_JELLY_PROBE_QUEUED) {
        if (atomic_compare_exchange_weak_explicit(
                &writer->status, &current, status,
                memory_order_release, memory_order_acquire))
            return;
    }
}

static int write_all(int fd, const char *bytes, size_t length)
{
    size_t written = 0;
    while (written < length) {
        ssize_t amount = write(fd, bytes + written, length - written);
        if (amount < 0 && errno == EINTR)
            continue;
        if (amount <= 0)
            return amount == 0 ? EIO : errno;
        written += (size_t)amount;
    }
    return 0;
}

static int appendf(char *b, size_t cap, size_t *n, const char *fmt, ...)
{
    va_list ap; va_start(ap, fmt); int k=vsnprintf(b + *n, cap - *n, fmt, ap); va_end(ap);
    if (k < 0 || (size_t)k >= cap - *n)
        return ENOSPC;
    *n += (size_t)k;
    return 0;
}
static int num(char *b,size_t cap,size_t*n,float x){ return isfinite(x)?appendf(b,cap,n,"%.9g",(double)x):appendf(b,cap,n,"null"); }
static int vec(char*b,size_t c,size_t*n,const float*v,int count){ int e=appendf(b,c,n,"["); for(int i=0;!e&&i<count;i++){if(i)e=appendf(b,c,n,",");if(!e)e=num(b,c,n,v[i]);}if(!e)e=appendf(b,c,n,"]");return e; }
static int append_json_string(char *b, size_t cap, size_t *n, const char *value)
{
    int e = appendf(b, cap, n, "\"");
    for (const unsigned char *p = (const unsigned char *)value; !e && *p; p++) {
        if (*p == '"' || *p == '\\')
            e = appendf(b, cap, n, "\\%c", *p);
        else if (*p < 0x20)
            e = appendf(b, cap, n, "\\u%04x", (unsigned)*p);
        else
            e = appendf(b, cap, n, "%c", *p);
    }
    if (!e) e = appendf(b, cap, n, "\"");
    return e;
}

static int append_nullable_int(char *b, size_t cap, size_t *n, int value)
{
    return value < 0 ? appendf(b, cap, n, "null") : appendf(b, cap, n, "%d", value);
}

static int append_nullable_double(char *b, size_t cap, size_t *n, double value)
{
    return isfinite(value) ? appendf(b, cap, n, "%.9g", value) : appendf(b, cap, n, "null");
}

static const char *stage_status_name(int status)
{
    switch (status) {
    case MPVRX_JELLY_STAGE_NOT_REACHED: return "not_reached";
    case MPVRX_JELLY_STAGE_OK: return "success";
    case MPVRX_JELLY_STAGE_FAILED: return "failure";
    case MPVRX_JELLY_STAGE_SKIPPED: return "skipped";
    default: return "unknown";
    }
}

static const char *readback_status_name(int status)
{
    switch (status) {
    case MPVRX_JELLY_READBACK_NOT_SUBMITTED: return "not_submitted";
    case MPVRX_JELLY_READBACK_PENDING: return "pending";
    case MPVRX_JELLY_READBACK_READY: return "ready";
    case MPVRX_JELLY_READBACK_ERROR: return "error";
    default: return "unknown";
    }
}

static const char *jelly_reason_name(int reason)
{
    switch (reason) {
    case 0: return "decision_not_recorded";
    case 1: return "center_delta_below_threshold";
    case 2: return "phase_endpoint";
    case 3: return "neighbor_scan_pending";
    case 4: return "insufficient_neighbor_support";
    case 5: return "fill_below_threshold";
    case 6: return "jelly_applied";
    case 7: return "zero_flow";
    case 9: return "scene_cut";
    default: return "unknown_reason_code";
    }
}

static const char *diagnostic_reason(const struct mpvrx_jelly_probe_diagnostics *d)
{
    if (!d) return "stage_data_not_observed";
    if (d->setup_status == MPVRX_JELLY_STAGE_FAILED) return "probe_setup_failed";
    if (d->buffer_status == MPVRX_JELLY_STAGE_FAILED) return "gpu_buffer_setup_failed";
    if (d->setup_status == MPVRX_JELLY_STAGE_OK && d->motion_render_callback_count == 0)
        return "no_motion_render_callback";
    if (d->motion_render_callback_count > 0 && d->armed_render_callback_count == 0)
        return "probe_not_armed_during_motion_callback";
    if (d->eligible_pair_count == 0 && d->source_bounds_observed && d->pixel_in_bounds == 0)
        return "target_pixel_out_of_bounds";
    if (d->eligible_pair_count == 0 && d->out_of_bounds_count > 0 &&
        d->alpha_below_count == 0 && d->alpha_above_count == 0 && d->alpha_invalid_count == 0)
        return "target_pixel_out_of_bounds";
    if (d->eligible_pair_count == 0 && d->out_of_bounds_count == 0 &&
        (d->alpha_below_count > 0 || d->alpha_above_count > 0 || d->alpha_invalid_count > 0))
        return "alpha_outside_probe_range";
    if (d->eligible_pair_count == 0) return "no_eligible_frame_pair";
    if (d->gpu_submit_count == 0) return "no_gpu_dispatch_observed";
    if (d->gpu_last_poll_status == MPVRX_JELLY_READBACK_ERROR) return "gpu_readback_error";
    if (d->gpu_last_poll_status == MPVRX_JELLY_READBACK_PENDING) return "gpu_readback_pending_at_finalize";
    if (d->gpu_last_poll_status == MPVRX_JELLY_READBACK_READY && d->pixel_evaluated == 0)
        return "target_pixel_not_evaluated";
    if (d->activation == 0) return "jelly_rejected_or_skipped";
    return "native_sample_not_available";
}

static unsigned long long report_timestamp_ms(void)
{
    struct timespec now;
    if (clock_gettime(CLOCK_REALTIME, &now) != 0 || now.tv_sec < 0)
        return 0;
    return (unsigned long long)now.tv_sec * 1000ULL + (unsigned long long)now.tv_nsec / 1000000ULL;
}

static int write_json_payload(struct mpvrx_jelly_probe_writer *writer, const char *json, size_t n)
{
    int e = 0;
    int fd = mkstemp(writer->temp_template);
    if (fd < 0) return errno;
    e = write_all(fd, json, n);
    if (!e && fsync(fd) != 0) e = errno;
    if (close(fd) != 0 && !e) e = errno;
    if (e) { unlink(writer->temp_template); return e; }
    if (rename(writer->temp_template, writer->final_path) != 0) {
        e = errno; unlink(writer->temp_template); return e;
    }
    return 0;
}

static int write_no_sample_json(struct mpvrx_jelly_probe_writer *writer,
                                const struct mpvrx_jelly_probe_diagnostics *d,
                                int snapshot_provided)
{
    char json[8192]; size_t n = 0; int e = 0;
    const char *reason = snapshot_provided ? diagnostic_reason(d) : "stage_data_not_observed";
#define A(...) do { if(!e)e=appendf(json,sizeof json,&n,__VA_ARGS__); } while(0)
    A("{\"report_type\":\"mpv-infinity-jelly-probe-report\",\"schema_version\":3,\"status\":\"no_sample\",\"reason\":\"%s\",\"stage_snapshot\":\"%s\",\"created_at_epoch_ms\":%llu,\"report_path\":", reason, snapshot_provided ? "provided" : "not_observed", report_timestamp_ms());
    if (!e) e = append_json_string(json, sizeof json, &n, writer->final_path);
    A(",\"target\":{\"media_hint\":\"599740.mkv\",\"current_file_verified\":false,\"pts_window_seconds\":[1.46,1.63],\"pair_rule\":\"pair PTS interval overlaps [1.46,1.63]\",\"pixel\":[%d,%d],\"fallback_rule\":\"also probe eligible pairs outside [1.46,1.63] (including earlier or later pairs) until the first activation; if none activates, finalization saves the last evaluated skip\",\"eligible_alpha_range\":[0.35,0.65]},", d->pixel_x, d->pixel_y);
    A("\"stages\":{\"arm_setup\":{\"status\":\"%s\",\"error_code\":", stage_status_name(d->setup_status));
    if (!e) e = append_nullable_int(json, sizeof json, &n, d->setup_error);
    A(",\"writer_init_status\":\"%s\",\"writer_init_error_code\":", stage_status_name(d->writer_init_status));
    if (!e) e = append_nullable_int(json, sizeof json, &n, d->writer_init_error);
    A(",\"gpu_buffer_status\":\"%s\",\"gpu_buffer_error_code\":", stage_status_name(d->buffer_status));
    if (!e) e = append_nullable_int(json, sizeof json, &n, d->buffer_error);
    A(",\"report_path\":");
    if (!e) e = append_json_string(json, sizeof json, &n, writer->final_path);
    A("},\"target_pixel\":{\"status\":\"%s\",\"evaluated\":", d->pixel_evaluated == 1 ? "evaluated" : d->pixel_evaluated == 0 ? "not_evaluated" : "not_observed");
    if (d->pixel_evaluated < 0) A("null"); else A("%s", d->pixel_evaluated ? "true" : "false");
    A(",\"source_bounds\":{\"status\":\"%s\",\"width\":", d->source_bounds_observed ? "observed" : "not_observed");
    if (!e) e = append_nullable_int(json, sizeof json, &n, d->source_bounds_observed ? d->source_width : -1);
    A(",\"height\":");
    if (!e) e = append_nullable_int(json, sizeof json, &n, d->source_bounds_observed ? d->source_height : -1);
    A(",\"pixel_in_bounds\":");
    if (!e && d->pixel_in_bounds < 0) e = appendf(json, sizeof json, &n, "null");
    else if (!e) e = appendf(json, sizeof json, &n, "%s", d->pixel_in_bounds ? "true" : "false");
    A("}},\"render_callbacks\":{\"status\":\"%s\",\"count\":", d->setup_status == MPVRX_JELLY_STAGE_OK ? "observed" : "not_reached");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->motion_render_callback_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"while_armed\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->armed_render_callback_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"out_of_bounds_count\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->out_of_bounds_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"alpha_below_range_count\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->alpha_below_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"alpha_above_range_count\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->alpha_above_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"alpha_invalid_count\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->alpha_invalid_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"pending_readback_count\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->pending_readback_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"rejection_mask_bits\":\"1=target_pixel_out_of_bounds,2=alpha_below_range,4=alpha_above_range,8=alpha_not_finite\"");
    A(",\"last_candidate\":");
    if (d->last_candidate_observed) {
        A("{\"pts_valid\":%s,\"pts\":[", d->last_candidate_pts_valid ? "true" : "false");
        if (!e) e = append_nullable_double(json, sizeof json, &n, d->last_candidate_pts_valid ? d->last_candidate_pts[0] : NAN);
        A(",");
        if (!e) e = append_nullable_double(json, sizeof json, &n, d->last_candidate_pts_valid ? d->last_candidate_pts[1] : NAN);
        A("],\"alpha\":");
        if (!e) e = append_nullable_double(json, sizeof json, &n, d->last_candidate_alpha);
        A(",\"selection\":\"%s\",\"source_width\":%d,\"source_height\":%d,\"pixel_in_bounds\":%s,\"eligible\":%s,\"readback_pending\":%s,\"rejection_mask\":%u}",
          d->last_candidate_selection_mode == 1 ? "target_window" : "fallback",
          d->last_candidate_source_width, d->last_candidate_source_height,
          d->last_candidate_pixel_in_bounds ? "true" : "false",
          d->last_candidate_eligible ? "true" : "false",
          d->last_candidate_pending ? "true" : "false",
          d->last_candidate_rejection_mask);
    } else {
        A("null");
    }
    A("},\"eligible_frame_pairs\":{\"status\":\"%s\",\"eligible_count\":", d->setup_status == MPVRX_JELLY_STAGE_OK ? "observed" : "not_reached");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->eligible_pair_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"target_window_count\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->target_pair_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"fallback_count\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->fallback_pair_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"last_pair\":");
    if (d->last_pair_observed) {
        A("{\"pts\":[");
        if (!e) e = append_nullable_double(json, sizeof json, &n, d->last_pts_valid ? d->last_pts[0] : NAN);
        A(",");
        if (!e) e = append_nullable_double(json, sizeof json, &n, d->last_pts_valid ? d->last_pts[1] : NAN);
        A("],\"alpha\":");
        if (!e) e = append_nullable_double(json, sizeof json, &n, d->last_alpha);
        A(",\"selection\":\"%s\"}", d->last_selection_mode == 1 ? "target_window" : d->last_selection_mode == 2 ? "fallback" : "unknown");
    } else {
        A("null");
    }
    A("},\"jelly\":{\"status\":\"%s\",\"reason_code\":", d->activation == 1 ? "activated" : d->activation == 0 ? "rejected_or_skipped" : "not_observed");
    if (!e) e = append_nullable_int(json, sizeof json, &n, d->jelly_reason_code);
    A(",\"reason\":\"%s\",\"fill\":", d->jelly_reason_code < 0 ? "not_observed" : jelly_reason_name(d->jelly_reason_code));
    if (!e) e = append_nullable_double(json, sizeof json, &n, d->jelly_fill);
    A("},\"gpu_readback\":{\"status\":\"%s\",\"submitted_count\":", d->setup_status == MPVRX_JELLY_STAGE_OK ? readback_status_name(d->gpu_last_poll_status) : "not_reached");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->gpu_submit_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"poll_count\":");
    if (!e) e = d->setup_status == MPVRX_JELLY_STAGE_OK
        ? appendf(json, sizeof json, &n, "%u", d->gpu_poll_count)
        : appendf(json, sizeof json, &n, "null");
    A(",\"pixel_evaluated\":");
    if (d->pixel_evaluated < 0) A("null"); else A("%s", d->pixel_evaluated ? "true" : "false");
    A("},\"writer\":{\"status\":\"completed\",\"file_result\":\"success\",\"error_code\":0}}}\n");
#undef A
    if (e) return e;
    return write_json_payload(writer, json, n);
}

static int write_capture_json(struct mpvrx_jelly_probe_writer *writer,
                              const unsigned char record[MPVRX_JELLY_PROBE_RECORD_BYTES])
{
    float values[256]; memcpy(values, record, sizeof values); const float *v=values; char json[16384]; size_t n=0; int e=0;
    uint32_t u[4]; memcpy(u,record+58*16,sizeof u); uint64_t id0=(uint64_t)u[0]|((uint64_t)u[1]<<32), id1=(uint64_t)u[2]|((uint64_t)u[3]<<32);
    int evaluated = mpvrx_jelly_probe_record_was_evaluated(record);
    int activation = evaluated && mpvrx_jelly_probe_record_is_activation(record);
    int reason_code = isfinite(v[1]) ? (int)v[1] : -1;
#define A(...) do { if(!e)e=appendf(json,sizeof json,&n,__VA_ARGS__); } while(0)
#define V(i) do { if(!e)e=vec(json,sizeof json,&n,v+(i)*4,4); } while(0)
    A("{\"report_type\":\"mpv-infinity-jelly-probe-report\",\"schema_version\":3,\"status\":\"%s\",\"reason\":\"%s\",\"created_at_epoch_ms\":%llu,\"report_path\":", activation ? "activated" : evaluated ? "rejected" : "no_sample", evaluated ? jelly_reason_name(reason_code) : "target_pixel_not_evaluated", report_timestamp_ms());
    if (!e) e = append_json_string(json, sizeof json, &n, writer->final_path);
    A(",\"capture\":{\"id0\":\"%llu\",\"id1\":\"%llu\",\"pts\":[",(unsigned long long)id0,(unsigned long long)id1); if(!e)e=num(json,sizeof json,&n,v[59*4]); A(","); if(!e)e=num(json,sizeof json,&n,v[59*4+1]); A("],\"alpha\":"); if(!e)e=num(json,sizeof json,&n,v[59*4+2]); A(",\"selection\":");if(!e)e=num(json,sizeof json,&n,v[59*4+3]);A(",\"pixel_source\":");V(60);A(",\"map\":");V(61);A("},\"decision\":");V(0);A(",\"center\":{");
    const char *cn[]={"forward_warp","backward_warp","color0","color1","metric0","metric1","color_delta_change","neighbor_counts_weights"};
    for(int i=0;i<8;i++){if(i)A(",");A("\"%s\":",cn[i]);V(i+1);} A("},\"neighbors\":[");
    for(int i=0;i<8;i++){int b=9+i*6;if(i)A(",");A("{\"index\":%d,\"forward_warp\":",i);V(b);A(",\"backward_warp\":");V(b+1);A(",\"color0_error0\":");V(b+2);A(",\"color1_error1\":");V(b+3);A(",\"weights_votes\":");V(b+4);A(",\"rejection\":");if(!e)e=num(json,sizeof json,&n,v[(b+5)*4+2]);A("}");}
    A("],\"support\":");V(57);A(",\"stages\":{\"target_pixel\":{\"status\":\"%s\",\"evaluated\":%s,\"pixel\":[", evaluated ? "evaluated" : "not_evaluated", evaluated ? "true" : "false");
    if (!e) { e = num(json, sizeof json, &n, v[60*4]); }
    A(",");
    if (!e) { e = num(json, sizeof json, &n, v[60*4+1]); }
    A("],\"source_width\":");
    if (!e) { e = num(json, sizeof json, &n, v[60*4+2]); }
    A(",\"source_height\":");
    if (!e) { e = num(json, sizeof json, &n, v[60*4+3]); }
    int bounds_known = isfinite(v[60*4+2]) && isfinite(v[60*4+3]);
    int in_bounds = bounds_known && v[60*4] >= 0 && v[60*4+1] >= 0 && v[60*4] < v[60*4+2] && v[60*4+1] < v[60*4+3];
    A(",\"pixel_in_bounds\":%s},\"jelly\":{\"status\":\"%s\",\"reason_code\":", bounds_known ? (in_bounds ? "true" : "false") : "null", activation ? "activated" : evaluated ? "rejected_or_skipped" : "not_observed");
    if(reason_code < 0) A("null"); else A("%d", reason_code);
    A(",\"reason\":\"%s\",\"fill\":", evaluated ? jelly_reason_name(reason_code) : "not_observed"); if(!e)e=num(json,sizeof json,&n,v[57*4+3]);
    A("},\"gpu_readback\":{\"status\":\"ready\",\"poll_count\":null},\"writer\":{\"status\":\"completed\",\"file_result\":\"success\",\"error_code\":0}}}\n");
#undef A
#undef V
    if (e) return e;
    return write_json_payload(writer, json, n);
}

static void *mpvrx_jelly_probe_worker_main(void *opaque)
{
    struct mpvrx_jelly_probe_writer *writer = opaque;
    unsigned char record[MPVRX_JELLY_PROBE_RECORD_BYTES];

    for (;;) {
        while (sem_wait(&writer->queue.wake) != 0) {
            if (errno == EINTR)
                continue;
            pw_set_terminal_status(writer, MPVRX_JELLY_PROBE_FAILED);
            atomic_store_explicit(&writer->worker_exited, 1, memory_order_release);
            return NULL;
        }

#ifdef MPVRX_JELLY_PROBE_TEST_HOOKS
        mpvrx_jelly_probe_test_hook(MPVRX_JELLY_TEST_BEFORE_POP);
#endif
        while (mpvrx_jelly_probe_queue_try_pop(&writer->queue, record) == MPVRX_JELLY_QUEUE_OK) {
            int error = write_capture_json(writer, record);
            atomic_store_explicit(&writer->last_error, error, memory_order_release);
            pw_set_terminal_status(writer, error ? MPVRX_JELLY_PROBE_FAILED : MPVRX_JELLY_PROBE_COMPLETE);
            atomic_fetch_add_explicit(&writer->completion_count, 1, memory_order_relaxed);
        }

        if (mpvrx_jelly_probe_queue_is_closed_and_drained(&writer->queue)) {
            if (atomic_load_explicit(&writer->completion_count, memory_order_acquire) == 0) {
                int snapshot_provided = atomic_load_explicit(&writer->diagnostics_ready,
                                                              memory_order_acquire);
                int error = write_no_sample_json(writer, &writer->diagnostics,
                                                 snapshot_provided);
                atomic_store_explicit(&writer->last_error, error, memory_order_release);
                atomic_store_explicit(&writer->status,
                    error ? MPVRX_JELLY_PROBE_FAILED : MPVRX_JELLY_PROBE_NO_SAMPLE,
                    memory_order_release);
                atomic_fetch_add_explicit(&writer->completion_count, 1, memory_order_relaxed);
            }
            break;
        }
    }

    atomic_store_explicit(&writer->worker_exited, 1, memory_order_release);
    return NULL;
}

static void *default_alloc(size_t size)
{
    return malloc(size);
}

static void default_dealloc(void *ptr)
{
    free(ptr);
}

int mpvrx_jelly_probe_writer_init(struct mpvrx_jelly_probe_writer *writer, const char *report_path)
{
    if (!writer || !report_path || !report_path[0])
        return EINVAL;
    memset(writer, 0, sizeof(*writer));
    writer->alloc = default_alloc;
    writer->dealloc = default_dealloc;

    size_t path_length = strlen(report_path);
    static const char temp_suffix[] = ".tmp.XXXXXX";
    if (path_length > SIZE_MAX - sizeof(temp_suffix))
        return ENAMETOOLONG;
    writer->final_path = writer->alloc(path_length + 1);
    if (!writer->final_path)
        return ENOMEM;
    memcpy(writer->final_path, report_path, path_length + 1);

    writer->temp_template = writer->alloc(path_length + sizeof(temp_suffix));
    if (!writer->temp_template) {
        writer->dealloc(writer->final_path);
        memset(writer, 0, sizeof(*writer));
        return ENOMEM;
    }
    memcpy(writer->temp_template, report_path, path_length);
    memcpy(writer->temp_template + path_length, temp_suffix, sizeof(temp_suffix));

    int error = mpvrx_jelly_probe_queue_init(&writer->queue);
    if (error) {
        writer->dealloc(writer->temp_template);
        writer->dealloc(writer->final_path);
        memset(writer, 0, sizeof(*writer));
        return error;
    }
    atomic_init(&writer->worker_exited, 0);
    atomic_init(&writer->status, MPVRX_JELLY_PROBE_IDLE);
    atomic_init(&writer->close_reason, MPVRX_JELLY_CLOSE_NONE);
    atomic_init(&writer->completion_count, 0);
    atomic_init(&writer->diagnostics_ready, 0);
    atomic_init(&writer->last_error, 0);
    diagnostics_reset(&writer->diagnostics);

    error = pthread_create(&writer->worker, NULL, mpvrx_jelly_probe_worker_main, writer);
    if (error) {
        mpvrx_jelly_probe_queue_destroy(&writer->queue);
        writer->dealloc(writer->temp_template);
        writer->dealloc(writer->final_path);
        memset(writer, 0, sizeof(*writer));
        return error;
    }
    writer->worker_started = 1;
    return 0;
}

enum mpvrx_jelly_probe_capture_result mpvrx_jelly_probe_writer_arm(struct mpvrx_jelly_probe_writer *writer)
{
    if (!writer || !writer->worker_started)
        return MPVRX_JELLY_CAPTURE_INVALID;
    int expected = MPVRX_JELLY_PROBE_IDLE;
    if (atomic_compare_exchange_strong_explicit(
            &writer->status, &expected, MPVRX_JELLY_PROBE_ARMED,
            memory_order_acq_rel, memory_order_acquire))
        return MPVRX_JELLY_CAPTURE_ACCEPTED;
    if (expected == MPVRX_JELLY_PROBE_ARMED)
        return MPVRX_JELLY_CAPTURE_ALREADY_USED;
    if (expected == MPVRX_JELLY_PROBE_END_OF_STREAM || expected == MPVRX_JELLY_PROBE_STOPPED)
        return MPVRX_JELLY_CAPTURE_CLOSED;
    return MPVRX_JELLY_CAPTURE_ALREADY_USED;
}

enum mpvrx_jelly_probe_capture_result mpvrx_jelly_probe_writer_capture(
    struct mpvrx_jelly_probe_writer *writer,
    const void *ready_mapped_record)
{
    if (!writer || !writer->worker_started || !ready_mapped_record)
        return MPVRX_JELLY_CAPTURE_INVALID;

    int expected = MPVRX_JELLY_PROBE_ARMED;
    if (!atomic_compare_exchange_strong_explicit(
            &writer->status, &expected, MPVRX_JELLY_PROBE_CLAIMED,
            memory_order_acq_rel, memory_order_acquire)) {
        if (expected == MPVRX_JELLY_PROBE_IDLE)
            return MPVRX_JELLY_CAPTURE_NOT_ARMED;
        if (expected == MPVRX_JELLY_PROBE_END_OF_STREAM || expected == MPVRX_JELLY_PROBE_STOPPED)
            return MPVRX_JELLY_CAPTURE_CLOSED;
        return MPVRX_JELLY_CAPTURE_ALREADY_USED;
    }

    enum mpvrx_jelly_probe_queue_result result = mpvrx_jelly_probe_queue_try_push(&writer->queue,
                                                     ready_mapped_record);
    if (result == MPVRX_JELLY_QUEUE_FULL) {
        expected = MPVRX_JELLY_PROBE_CLAIMED;
        atomic_compare_exchange_strong_explicit(
            &writer->status, &expected, MPVRX_JELLY_PROBE_ARMED,
            memory_order_release, memory_order_acquire);
        return MPVRX_JELLY_CAPTURE_FULL;
    }
    if (result == MPVRX_JELLY_QUEUE_CLOSED || result == MPVRX_JELLY_QUEUE_INVALID) {
        int reason = atomic_load_explicit(&writer->close_reason, memory_order_acquire);
        int terminal = reason == MPVRX_JELLY_CLOSE_EOS ? MPVRX_JELLY_PROBE_END_OF_STREAM : MPVRX_JELLY_PROBE_STOPPED;
        expected = MPVRX_JELLY_PROBE_CLAIMED;
        atomic_compare_exchange_strong_explicit(
            &writer->status, &expected, terminal,
            memory_order_release, memory_order_acquire);
        return result == MPVRX_JELLY_QUEUE_CLOSED ? MPVRX_JELLY_CAPTURE_CLOSED : MPVRX_JELLY_CAPTURE_INVALID;
    }

    /* The worker may finish before this compare-exchange; never overwrite a
     * terminal completion with QUEUED. The callback retains no mapped pointer. */
    expected = MPVRX_JELLY_PROBE_CLAIMED;
    atomic_compare_exchange_strong_explicit(
        &writer->status, &expected, MPVRX_JELLY_PROBE_QUEUED,
        memory_order_release, memory_order_acquire);
    return MPVRX_JELLY_CAPTURE_ACCEPTED;
}

static void writer_request_close(struct mpvrx_jelly_probe_writer *writer,
                                enum mpvrx_jelly_probe_close_reason reason,
                                enum mpvrx_jelly_probe_writer_status no_capture_status)
{
    if (!writer || !writer->worker_started)
        return;
    int expected_reason = MPVRX_JELLY_CLOSE_NONE;
    atomic_compare_exchange_strong_explicit(
        &writer->close_reason, &expected_reason, reason,
        memory_order_acq_rel, memory_order_acquire);

    int status = atomic_load_explicit(&writer->status, memory_order_acquire);
    while (status == MPVRX_JELLY_PROBE_IDLE || status == MPVRX_JELLY_PROBE_ARMED) {
        if (atomic_compare_exchange_weak_explicit(
                &writer->status, &status, no_capture_status,
                memory_order_release, memory_order_acquire))
            break;
    }
    mpvrx_jelly_probe_queue_close(&writer->queue);
}

void mpvrx_jelly_probe_writer_finish(
    struct mpvrx_jelly_probe_writer *writer,
    const struct mpvrx_jelly_probe_diagnostics *diagnostics)
{
    if (!writer || !writer->worker_started)
        return;
    if (diagnostics) {
        writer->diagnostics = *diagnostics;
        atomic_store_explicit(&writer->diagnostics_ready, 1, memory_order_release);
    }
    mpvrx_jelly_probe_writer_request_stop(writer);
}

void mpvrx_jelly_probe_writer_request_stop(struct mpvrx_jelly_probe_writer *writer)
{
    writer_request_close(writer, MPVRX_JELLY_CLOSE_STOP, MPVRX_JELLY_PROBE_STOPPED);
}

int mpvrx_jelly_probe_writer_join(struct mpvrx_jelly_probe_writer *writer)
{
    if (!writer || !writer->worker_started)
        return EINVAL;
    if (writer->worker_joined)
        return 0;
    mpvrx_jelly_probe_writer_request_stop(writer);
    int error = pthread_join(writer->worker, NULL);
    if (!error)
        writer->worker_joined = 1;
    return error;
}

void mpvrx_jelly_probe_writer_destroy(struct mpvrx_jelly_probe_writer *writer)
{
    if (!writer)
        return;
    if (writer->worker_started && !writer->worker_joined && mpvrx_jelly_probe_writer_join(writer) != 0)
        return; /* Leak safely rather than freeing storage under a live worker. */

    if (writer->queue.initialized)
        mpvrx_jelly_probe_queue_destroy(&writer->queue);
    if (writer->temp_template)
        writer->dealloc(writer->temp_template);
    if (writer->final_path)
        writer->dealloc(writer->final_path);
    memset(writer, 0, sizeof(*writer));
}

enum mpvrx_jelly_probe_writer_status mpvrx_jelly_probe_writer_status(const struct mpvrx_jelly_probe_writer *writer)
{
    if (!writer)
        return MPVRX_JELLY_PROBE_FAILED;
    return (enum mpvrx_jelly_probe_writer_status)atomic_load_explicit(&writer->status, memory_order_acquire);
}

int mpvrx_jelly_probe_writer_error(const struct mpvrx_jelly_probe_writer *writer)
{
    if (!writer)
        return EINVAL;
    return atomic_load_explicit(&writer->last_error, memory_order_acquire);
}
