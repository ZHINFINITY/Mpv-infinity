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
static int write_json_atomically(struct mpvrx_jelly_probe_writer *writer, const unsigned char record[MPVRX_JELLY_PROBE_RECORD_BYTES])
{
    float values[256]; memcpy(values, record, sizeof values); const float *v=values; char json[16384]; size_t n=0; int e=0;
    uint32_t u[4]; memcpy(u,record+58*16,sizeof u); uint64_t id0=(uint64_t)u[0]|((uint64_t)u[1]<<32), id1=(uint64_t)u[2]|((uint64_t)u[3]<<32);
#define A(...) do { if(!e)e=appendf(json,sizeof json,&n,__VA_ARGS__); } while(0)
#define V(i) do { if(!e)e=vec(json,sizeof json,&n,v+(i)*4,4); } while(0)
    A("{\"schema_version\":1,\"capture\":{\"id0\":\"%llu\",\"id1\":\"%llu\",\"pts\":[",(unsigned long long)id0,(unsigned long long)id1); if(!e)e=num(json,sizeof json,&n,v[59*4]); A(","); if(!e)e=num(json,sizeof json,&n,v[59*4+1]); A("],\"alpha\":"); if(!e)e=num(json,sizeof json,&n,v[59*4+2]); A(",\"selection\":");if(!e)e=num(json,sizeof json,&n,v[59*4+3]);A(",\"pixel_source\":");V(60);A(",\"map\":");V(61);A("},\"decision\":");V(0);A(",\"center\":{");
    const char *cn[]={"forward_warp","backward_warp","color0","color1","metric0","metric1","color_delta_change","neighbor_counts_weights"};
    for(int i=0;i<8;i++){if(i)A(",");A("\"%s\":",cn[i]);V(i+1);} A("},\"neighbors\":[");
    for(int i=0;i<8;i++){int b=9+i*6;if(i)A(",");A("{\"index\":%d,\"forward_warp\":",i);V(b);A(",\"backward_warp\":");V(b+1);A(",\"color0_error0\":");V(b+2);A(",\"color1_error1\":");V(b+3);A(",\"weights_votes\":");V(b+4);A(",\"rejection\":");if(!e)e=num(json,sizeof json,&n,v[(b+5)*4+2]);A("}");}
    A("],\"support\":");V(57);A("}\n");
#undef A
#undef V
    if (e) return e;
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
            int error = write_json_atomically(writer, record);
            pw_set_terminal_status(writer, error ? MPVRX_JELLY_PROBE_FAILED : MPVRX_JELLY_PROBE_COMPLETE);
            atomic_fetch_add_explicit(&writer->completion_count, 1, memory_order_relaxed);
        }

        if (mpvrx_jelly_probe_queue_is_closed_and_drained(&writer->queue))
            break;
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
