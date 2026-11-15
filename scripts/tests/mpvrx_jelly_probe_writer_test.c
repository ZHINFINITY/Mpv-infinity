#define _POSIX_C_SOURCE 200809L
#include "../../app/src/main/cpp/mpvflow/mpvrx_jelly_probe_writer.h"

#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

struct race {
    struct mpvrx_jelly_probe_writer *writer;
    unsigned char *record;
    pthread_barrier_t *barrier;
    enum mpvrx_jelly_probe_capture_result result;
};

static void *racer(void *opaque)
{
    struct race *r = opaque;
    pthread_barrier_wait(r->barrier);
    r->result = mpvrx_jelly_probe_writer_capture(r->writer, r->record);
    return NULL;
}

static void make_temp_path(char *path, size_t capacity, const char *prefix)
{
    const char *directory = getenv("TMPDIR");
    if (!directory || !directory[0])
        directory = "/tmp";
    int length = snprintf(path, capacity, "%s/%s-XXXXXX", directory, prefix);
    assert(length > 0 && (size_t)length < capacity);
    int fd = mkstemp(path);
    assert(fd >= 0);
    close(fd);
    assert(unlink(path) == 0);
}

static void wait_for_status(struct mpvrx_jelly_probe_writer *writer,
                            enum mpvrx_jelly_probe_writer_status expected)
{
    for (int i = 0; i < 5000 && mpvrx_jelly_probe_writer_status(writer) != expected; i++) {
        struct timespec pause = {0, 1000000};
        nanosleep(&pause, NULL);
    }
    assert(mpvrx_jelly_probe_writer_status(writer) == expected);
}

static char *read_report(const char *path, char *json, size_t capacity)
{
    int fd = open(path, O_RDONLY);
    assert(fd >= 0);
    ssize_t length = read(fd, json, capacity - 1);
    close(fd);
    assert(length > 0 && (size_t)length < capacity);
    json[length] = '\0';
    return json;
}

static struct mpvrx_jelly_probe_diagnostics observed_rejection(void)
{
    struct mpvrx_jelly_probe_diagnostics d = {0};
    d.setup_status = MPVRX_JELLY_STAGE_OK;
    d.setup_error = 0;
    d.writer_init_status = MPVRX_JELLY_STAGE_OK;
    d.writer_init_error = 0;
    d.buffer_status = MPVRX_JELLY_STAGE_OK;
    d.buffer_error = 0;
    d.pixel_x = 1352;
    d.pixel_y = 224;
    d.source_bounds_observed = 1;
    d.source_width = 1920;
    d.source_height = 1080;
    d.pixel_in_bounds = 1;
    d.eligible_pair_count = 1;
    d.target_pair_count = 1;
    d.last_pair_observed = 1;
    d.last_pts_valid = 1;
    d.last_pts[0] = 1.47;
    d.last_pts[1] = 1.61;
    d.last_alpha = 0.5;
    d.last_selection_mode = 1;
    d.render_attempt_count = 1;
    d.gpu_submit_count = 1;
    d.gpu_poll_count = 2;
    d.gpu_last_poll_status = MPVRX_JELLY_READBACK_READY;
    d.pixel_evaluated = 1;
    d.jelly_reason_code = 5;
    d.activation = 0;
    d.jelly_fill = 0.18;
    return d;
}

int main(void)
{
    char path[512];
    make_temp_path(path, sizeof(path), "mpvrx-jelly-writer");
    struct mpvrx_jelly_probe_writer w;
    assert(mpvrx_jelly_probe_writer_init(&w, path) == 0);

    unsigned char rec[MPVRX_JELLY_PROBE_RECORD_BYTES] = {0};
    float f[256] = {0};
    uint64_t a = UINT64_C(0xfedcba9876543210), b = UINT64_C(0x123456789abcdef0);
    uint32_t ids[4] = {(uint32_t)a, (uint32_t)(a >> 32), (uint32_t)b, (uint32_t)(b >> 32)};
    memcpy(rec + 58 * 16, ids, sizeof ids);
    memcpy(f + 59 * 4, (float[]){12.5f, 25.75f, .375f, 1.f}, 4 * sizeof(float));
    memcpy(f + 60 * 4, (float[]){1352.f, 224.f, 1920.f, 1080.f}, 4 * sizeof(float));
    f[0] = 1;
    f[1] = 6;
    f[57 * 4 + 3] = NAN;
    memcpy(rec, f, sizeof rec);
    /* Restore raw ID words after float payload initialization. */
    memcpy(rec + 58 * 16, ids, sizeof ids);
    assert(mpvrx_jelly_probe_record_was_evaluated(rec));
    assert(mpvrx_jelly_probe_record_is_activation(rec));
    float reason = 5.0f;
    memcpy(rec + sizeof(float), &reason, sizeof reason);
    assert(!mpvrx_jelly_probe_record_is_activation(rec));
    reason = NAN;
    memcpy(rec + sizeof(float), &reason, sizeof reason);
    assert(!mpvrx_jelly_probe_record_is_activation(rec));
    reason = 6.0f;
    memcpy(rec + sizeof(float), &reason, sizeof reason);
    assert(mpvrx_jelly_probe_record_is_activation(rec));
    assert(mpvrx_jelly_probe_writer_capture(&w, rec) == MPVRX_JELLY_CAPTURE_NOT_ARMED);
    assert(mpvrx_jelly_probe_writer_arm(&w) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    assert(mpvrx_jelly_probe_writer_arm(&w) == MPVRX_JELLY_CAPTURE_ALREADY_USED);
    assert(mpvrx_jelly_probe_writer_capture(&w, rec) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    assert(mpvrx_jelly_probe_writer_capture(&w, rec) == MPVRX_JELLY_CAPTURE_ALREADY_USED);
    wait_for_status(&w, MPVRX_JELLY_PROBE_COMPLETE);
    char json[16384];
    read_report(path, json, sizeof json);
    assert(strstr(json, "\"report_type\":\"mpv-infinity-jelly-probe-report\""));
    assert(strstr(json, "\"schema_version\":2"));
    assert(strstr(json, "\"status\":\"activated\""));
    assert(strstr(json, "\"id0\":\"18364758544493064720\""));
    assert(strstr(json, "\"id1\":\"1311768467463790320\""));
    assert(strstr(json, "12.5") && strstr(json, "25.75"));
    assert(strstr(json, "\"neighbors\":["));
    assert(strstr(json, "\"rejection\":0"));
    assert(strstr(json, "\"pixel_in_bounds\":true"));
    assert(strstr(json, "\"gpu_readback\":{\"status\":\"ready\""));
    assert(strstr(json, "\"file_result\":\"success\""));
    assert(strstr(json, "null"));
    assert(mpvrx_jelly_probe_writer_error(&w) == 0);
    assert(mpvrx_jelly_probe_writer_join(&w) == 0);
    mpvrx_jelly_probe_writer_destroy(&w);
    unlink(path);
    puts("ok activation record, stage fields, IDs/PTS, NaN null, atomic report and safe join");

    char no_sample_path[512];
    make_temp_path(no_sample_path, sizeof(no_sample_path), "mpvrx-jelly-no-sample");
    struct mpvrx_jelly_probe_writer no_sample;
    assert(mpvrx_jelly_probe_writer_init(&no_sample, no_sample_path) == 0);
    assert(mpvrx_jelly_probe_writer_arm(&no_sample) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    struct mpvrx_jelly_probe_diagnostics diagnostics = observed_rejection();
    mpvrx_jelly_probe_writer_finish(&no_sample, &diagnostics);
    wait_for_status(&no_sample, MPVRX_JELLY_PROBE_NO_SAMPLE);
    assert(mpvrx_jelly_probe_writer_error(&no_sample) == 0);
    read_report(no_sample_path, json, sizeof json);
    assert(strstr(json, "\"status\":\"no_sample\""));
    assert(strstr(json, "\"reason\":\"jelly_rejected_or_skipped\""));
    assert(strstr(json, "\"stage_snapshot\":\"provided\""));
    assert(strstr(json, "if PTS is unavailable or an eligible pair begins after 1.63s, probe fallback pairs"));
    assert(strstr(json, "\"pts_window_seconds\":[1.46,1.63]"));
    assert(strstr(json, "\"pixel\":[1352,224]"));
    assert(strstr(json, "\"eligible_count\":1"));
    assert(strstr(json, "\"pts\":[1.47,1.61]"));
    assert(strstr(json, "\"alpha\":0.5"));
    assert(strstr(json, "\"selection\":\"target_window\""));
    assert(strstr(json, "\"width\":1920") && strstr(json, "\"height\":1080"));
    assert(strstr(json, "\"pixel_in_bounds\":true"));
    assert(strstr(json, "\"reason_code\":5"));
    assert(strstr(json, "\"poll_count\":2"));
    assert(strstr(json, "\"file_result\":\"success\""));
    assert(mpvrx_jelly_probe_writer_join(&no_sample) == 0);
    mpvrx_jelly_probe_writer_destroy(&no_sample);
    unlink(no_sample_path);
    puts("ok no-sample report preserves observed pair, bounds, Jelly rejection, poll and writer stages");

    char setup_path[512];
    make_temp_path(setup_path, sizeof(setup_path), "mpvrx-jelly-setup-failure");
    struct mpvrx_jelly_probe_writer setup_failure;
    assert(mpvrx_jelly_probe_writer_init(&setup_failure, setup_path) == 0);
    struct mpvrx_jelly_probe_diagnostics failed_setup = {0};
    failed_setup.setup_status = MPVRX_JELLY_STAGE_FAILED;
    failed_setup.setup_error = ENOTSUP;
    failed_setup.writer_init_status = MPVRX_JELLY_STAGE_OK;
    failed_setup.writer_init_error = 0;
    failed_setup.buffer_status = MPVRX_JELLY_STAGE_FAILED;
    failed_setup.buffer_error = ENOTSUP;
    failed_setup.pixel_x = 1352;
    failed_setup.pixel_y = 224;
    failed_setup.source_width = failed_setup.source_height = -1;
    failed_setup.pixel_in_bounds = -1;
    failed_setup.last_pts_valid = failed_setup.last_selection_mode = -1;
    failed_setup.gpu_last_poll_status = MPVRX_JELLY_READBACK_NOT_SUBMITTED;
    failed_setup.pixel_evaluated = failed_setup.jelly_reason_code = failed_setup.activation = -1;
    failed_setup.jelly_fill = NAN;
    mpvrx_jelly_probe_writer_finish(&setup_failure, &failed_setup);
    wait_for_status(&setup_failure, MPVRX_JELLY_PROBE_NO_SAMPLE);
    read_report(setup_path, json, sizeof json);
    assert(strstr(json, "\"reason\":\"probe_setup_failed\""));
    assert(strstr(json, "\"status\":\"failure\",\"error_code\":95"));
    assert(strstr(json, "\"gpu_buffer_status\":\"failure\""));
    assert(strstr(json, "\"eligible_frame_pairs\":{\"status\":\"not_reached\",\"eligible_count\":null"));
    assert(strstr(json, "\"gpu_readback\":{\"status\":\"not_reached\",\"submitted_count\":null"));
    assert(strstr(json, "\"source_bounds\":{\"status\":\"not_observed\""));
    assert(mpvrx_jelly_probe_writer_join(&setup_failure) == 0);
    mpvrx_jelly_probe_writer_destroy(&setup_failure);
    unlink(setup_path);
    puts("ok no-sample report distinguishes native setup failure from unobserved stages");

    char unknown_path[512];
    make_temp_path(unknown_path, sizeof(unknown_path), "mpvrx-jelly-unobserved");
    struct mpvrx_jelly_probe_writer unknown;
    assert(mpvrx_jelly_probe_writer_init(&unknown, unknown_path) == 0);
    mpvrx_jelly_probe_writer_finish(&unknown, NULL);
    wait_for_status(&unknown, MPVRX_JELLY_PROBE_NO_SAMPLE);
    read_report(unknown_path, json, sizeof json);
    assert(strstr(json, "\"stage_snapshot\":\"not_observed\""));
    assert(strstr(json, "\"reason\":\"stage_data_not_observed\""));
    assert(strstr(json, "\"pixel\":[1352,224]"));
    assert(strstr(json, "\"source_bounds\":{\"status\":\"not_observed\""));
    assert(strstr(json, "\"eligible_count\":null"));
    assert(strstr(json, "\"gpu_readback\":{\"status\":\"not_reached\""));
    assert(mpvrx_jelly_probe_writer_join(&unknown) == 0);
    mpvrx_jelly_probe_writer_destroy(&unknown);
    unlink(unknown_path);
    puts("ok missing native snapshot remains explicitly unobserved");

    char directory_path[512];
    make_temp_path(directory_path, sizeof(directory_path), "mpvrx-jelly-writer-failure");
    assert(mkdir(directory_path, 0700) == 0);
    struct mpvrx_jelly_probe_writer file_failure;
    assert(mpvrx_jelly_probe_writer_init(&file_failure, directory_path) == 0);
    mpvrx_jelly_probe_writer_finish(&file_failure, &diagnostics);
    wait_for_status(&file_failure, MPVRX_JELLY_PROBE_FAILED);
    assert(mpvrx_jelly_probe_writer_error(&file_failure) != 0);
    assert(mpvrx_jelly_probe_writer_join(&file_failure) == 0);
    mpvrx_jelly_probe_writer_destroy(&file_failure);
    assert(rmdir(directory_path) == 0);
    puts("ok atomic output failure is surfaced as writer failure with errno");

    struct mpvrx_jelly_probe_writer bad;
    assert(mpvrx_jelly_probe_writer_init(&bad, NULL) != 0);
    puts("ok failed init");

    char racepath[512];
    make_temp_path(racepath, sizeof(racepath), "mpvrx-jelly-race");
    struct mpvrx_jelly_probe_writer rw;
    assert(mpvrx_jelly_probe_writer_init(&rw, racepath) == 0);
    assert(mpvrx_jelly_probe_writer_arm(&rw) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    pthread_barrier_t barrier;
    assert(pthread_barrier_init(&barrier, NULL, 2) == 0);
    unsigned char rr[MPVRX_JELLY_PROBE_RECORD_BYTES] = {0};
    struct race r = {&rw, rr, &barrier, MPVRX_JELLY_CAPTURE_INVALID};
    pthread_t thread;
    assert(pthread_create(&thread, NULL, racer, &r) == 0);
    pthread_barrier_wait(&barrier);
    mpvrx_jelly_probe_writer_request_stop(&rw);
    assert(pthread_join(thread, NULL) == 0);
    pthread_barrier_destroy(&barrier);
    assert(r.result == MPVRX_JELLY_CAPTURE_ACCEPTED ||
           r.result == MPVRX_JELLY_CAPTURE_CLOSED ||
           r.result == MPVRX_JELLY_CAPTURE_ALREADY_USED);
    assert(mpvrx_jelly_probe_writer_join(&rw) == 0);
    mpvrx_jelly_probe_writer_destroy(&rw);
    unlink(racepath);
    puts("ok concurrent close versus callback");
    return 0;
}
