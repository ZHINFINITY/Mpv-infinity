#ifndef MPVRX_JELLY_PROBE_WRITER_H
#define MPVRX_JELLY_PROBE_WRITER_H

#include <pthread.h>
#include <semaphore.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

#define MPVRX_JELLY_PROBE_RECORD_BYTES 1024u

/* One producer, one consumer, one fixed-size slot. */
enum mpvrx_jelly_probe_queue_result {
    MPVRX_JELLY_QUEUE_OK = 0,
    MPVRX_JELLY_QUEUE_FULL,
    MPVRX_JELLY_QUEUE_EMPTY,
    MPVRX_JELLY_QUEUE_CLOSED,
    MPVRX_JELLY_QUEUE_INVALID,
    MPVRX_JELLY_QUEUE_NOTIFY_ERROR,
};

struct mpvrx_jelly_probe_queue {
    _Atomic uint32_t gate; /* high bit = closed; low bits = producers in flight */
    _Atomic uint32_t head;
    _Atomic uint32_t tail;
    _Atomic int notify_error;
    unsigned char slot[MPVRX_JELLY_PROBE_RECORD_BYTES];
    sem_t wake;
    int initialized;
};

enum mpvrx_jelly_probe_writer_status {
    MPVRX_JELLY_PROBE_IDLE = 0,
    MPVRX_JELLY_PROBE_ARMED,
    MPVRX_JELLY_PROBE_CLAIMED,
    MPVRX_JELLY_PROBE_QUEUED,
    MPVRX_JELLY_PROBE_COMPLETE,
    MPVRX_JELLY_PROBE_FAILED,
    MPVRX_JELLY_PROBE_END_OF_STREAM,
    MPVRX_JELLY_PROBE_STOPPED,
    MPVRX_JELLY_PROBE_NO_SAMPLE,
};

enum mpvrx_jelly_probe_capture_result {
    MPVRX_JELLY_CAPTURE_ACCEPTED = 0,
    MPVRX_JELLY_CAPTURE_FULL,
    MPVRX_JELLY_CAPTURE_NOT_ARMED,
    MPVRX_JELLY_CAPTURE_ALREADY_USED,
    MPVRX_JELLY_CAPTURE_CLOSED,
    MPVRX_JELLY_CAPTURE_INVALID,
};

enum mpvrx_jelly_probe_close_reason {
    MPVRX_JELLY_CLOSE_NONE = 0,
    MPVRX_JELLY_CLOSE_EOS,
    MPVRX_JELLY_CLOSE_STOP,
};

enum mpvrx_jelly_probe_stage_status {
    MPVRX_JELLY_STAGE_UNKNOWN = -1,
    MPVRX_JELLY_STAGE_NOT_REACHED = 0,
    MPVRX_JELLY_STAGE_OK = 1,
    MPVRX_JELLY_STAGE_FAILED = 2,
    MPVRX_JELLY_STAGE_SKIPPED = 3,
};

enum mpvrx_jelly_probe_readback_status {
    MPVRX_JELLY_READBACK_UNKNOWN = -1,
    MPVRX_JELLY_READBACK_NOT_SUBMITTED = 0,
    MPVRX_JELLY_READBACK_PENDING = 1,
    MPVRX_JELLY_READBACK_READY = 2,
    MPVRX_JELLY_READBACK_ERROR = 3,
};

/* Owner-thread snapshot copied once at finalization; unknown values stay -1. */
struct mpvrx_jelly_probe_diagnostics {
    int setup_status, setup_error;
    int writer_init_status, writer_init_error;
    int buffer_status, buffer_error;
    int pixel_x, pixel_y;
    int source_bounds_observed, source_width, source_height, pixel_in_bounds;
    unsigned eligible_pair_count, target_pair_count, fallback_pair_count;
    int last_pair_observed, last_pts_valid;
    double last_pts[2], last_alpha;
    int last_selection_mode;
    unsigned render_attempt_count, render_failure_count, gpu_submit_count;
    unsigned gpu_poll_count;
    int gpu_last_poll_status;
    int pixel_evaluated, jelly_reason_code, activation;
    double jelly_fill;
};

/* Allocator hooks are retained internally for host fault-injection tests. */
typedef void *(*mpvrx_jelly_probe_alloc_fn)(size_t size);
typedef void (*mpvrx_jelly_probe_free_fn)(void *ptr);

struct mpvrx_jelly_probe_writer {
    struct mpvrx_jelly_probe_queue queue;
    pthread_t worker;
    int worker_started; /* owner/control thread only */
    int worker_joined;  /* owner/control thread only */
    _Atomic int worker_exited;
    _Atomic int status;
    _Atomic int close_reason;
    _Atomic unsigned completion_count;
    _Atomic int diagnostics_ready;
    _Atomic int last_error;
    struct mpvrx_jelly_probe_diagnostics diagnostics;
    char *final_path;
    char *temp_template;
    mpvrx_jelly_probe_alloc_fn alloc;
    mpvrx_jelly_probe_free_fn dealloc;
};

/* Queue primitives are exposed only to permit deterministic host tests. The
 * render callback uses mpvrx_jelly_probe_writer_capture(), not these functions directly. */
/* The VO may inspect the first decision vector only after a nonblocking poll
 * reports the mapped SSBO idle. Reason 6 means Jelly was applied at the probe pixel. */
int mpvrx_jelly_probe_record_is_activation(const void *mapped_record);
int mpvrx_jelly_probe_record_was_evaluated(const void *mapped_record);
/* Creates one background worker and prepares all path storage in advance.
 * No report-path allocation or filesystem work occurs in mpvrx_jelly_probe_writer_capture(). */
int mpvrx_jelly_probe_writer_init(struct mpvrx_jelly_probe_writer *writer, const char *report_path);

enum mpvrx_jelly_probe_capture_result mpvrx_jelly_probe_writer_arm(struct mpvrx_jelly_probe_writer *writer);
/* Call only after the libplacebo buffer is ready/mapped. Copies exactly 1 KiB
 * before returning; it never retains the supplied pointer. SPSC producer only. */
enum mpvrx_jelly_probe_capture_result mpvrx_jelly_probe_writer_capture(
    struct mpvrx_jelly_probe_writer *writer, const void *mapped_record);

/* Owner/control thread only. Copies the final stage snapshot and closes without
 * waiting; the background worker writes either the sample or a no-sample report. */
void mpvrx_jelly_probe_writer_finish(
    struct mpvrx_jelly_probe_writer *writer,
    const struct mpvrx_jelly_probe_diagnostics *diagnostics);

/* Both close requests are nonblocking and safe against a producer already in
 * mpvrx_jelly_probe_writer_capture(). Accepted records are drained; later captures are closed. */
void mpvrx_jelly_probe_writer_request_stop(struct mpvrx_jelly_probe_writer *writer);

/* Control/destructor thread only. Join drains accepted work; destroy joins
 * before freeing worker-owned paths or queue storage. Never call from render/VO. */
int mpvrx_jelly_probe_writer_join(struct mpvrx_jelly_probe_writer *writer);
void mpvrx_jelly_probe_writer_destroy(struct mpvrx_jelly_probe_writer *writer);

enum mpvrx_jelly_probe_writer_status mpvrx_jelly_probe_writer_status(const struct mpvrx_jelly_probe_writer *writer);
int mpvrx_jelly_probe_writer_error(const struct mpvrx_jelly_probe_writer *writer);

#ifdef MPVRX_JELLY_PROBE_TEST_HOOKS
enum mpvrx_jelly_probe_test_stage {
    MPVRX_JELLY_TEST_BEFORE_POP = 1,
    MPVRX_JELLY_TEST_BEFORE_RENAME,
};
void mpvrx_jelly_probe_test_hook(enum mpvrx_jelly_probe_test_stage stage);
#endif

#endif
