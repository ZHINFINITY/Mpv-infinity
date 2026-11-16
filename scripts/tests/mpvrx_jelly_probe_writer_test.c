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
    r->result = mpvrx_jelly_probe_writer_capture(r->writer, r->record, 1);
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

static void assert_invalid_shader_record(const unsigned char record[MPVRX_JELLY_PROBE_RECORD_BYTES],
                                         const char *prefix)
{
    char path[512], json[16384];
    make_temp_path(path, sizeof path, prefix);
    struct mpvrx_jelly_probe_writer writer;
    assert(mpvrx_jelly_probe_writer_init(&writer, path) == 0);
    assert(mpvrx_jelly_probe_writer_arm(&writer) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    assert(mpvrx_jelly_probe_writer_capture(&writer, record, 1) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    wait_for_status(&writer, MPVRX_JELLY_PROBE_COMPLETE);
    read_report(path, json, sizeof json);
    assert(strstr(json, "\"status\":\"no_sample\""));
    assert(strstr(json, "\"reason\":\"invalid_probe_metadata\""));
    assert(strstr(json, "\"status\":\"metadata_invalid\",\"evaluated\":false"));
    assert(strstr(json, "\"gpu_readback\":{\"status\":\"ready\",\"poll_count\":1}"));
    assert(mpvrx_jelly_probe_writer_join(&writer) == 0);
    mpvrx_jelly_probe_writer_destroy(&writer);
    unlink(path);
}

static void set_shader_float(unsigned char record[MPVRX_JELLY_PROBE_RECORD_BYTES],
                             int vec4_index, int component, float value)
{
    memcpy(record + vec4_index * 16 + component * sizeof(value), &value, sizeof(value));
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
    d.motion_views_enabled = 0;
    d.source_bounds_observed = 1;
    d.source_width = 1920;
    d.source_height = 1080;
    d.pixel_in_bounds = 1;
    d.eligible_pair_count = 1;
    d.target_pair_count = 1;
    d.motion_render_callback_count = 1;
    d.armed_render_callback_count = 1;
    d.last_candidate_observed = 1;
    d.last_candidate_pts_valid = 1;
    d.last_candidate_pts[0] = 1.47;
    d.last_candidate_pts[1] = 1.61;
    d.last_candidate_alpha = 0.5;
    d.last_candidate_selection_mode = 1;
    d.last_candidate_source_width = 1920;
    d.last_candidate_source_height = 1080;
    d.last_candidate_view_width = -1;
    d.last_candidate_view_height = -1;
    d.last_candidate_pixel_in_bounds = 1;
    d.last_candidate_eligible = 1;
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
    memcpy(f + 61 * 4, (float[]){480.f, 270.f, 1.f, 0.f}, 4 * sizeof(float));
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
    assert(mpvrx_jelly_probe_writer_capture(&w, rec, 1) == MPVRX_JELLY_CAPTURE_NOT_ARMED);
    assert(mpvrx_jelly_probe_writer_arm(&w) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    assert(mpvrx_jelly_probe_writer_arm(&w) == MPVRX_JELLY_CAPTURE_ALREADY_USED);
    assert(mpvrx_jelly_probe_writer_capture(&w, rec, 0) == MPVRX_JELLY_CAPTURE_INVALID);
    assert(mpvrx_jelly_probe_writer_capture(&w, rec, 1) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    assert(mpvrx_jelly_probe_writer_capture(&w, rec, 1) == MPVRX_JELLY_CAPTURE_ALREADY_USED);
    wait_for_status(&w, MPVRX_JELLY_PROBE_COMPLETE);
    char json[16384];
    read_report(path, json, sizeof json);
    assert(strstr(json, "\"report_type\":\"mpv-infinity-jelly-probe-report\""));
    assert(strstr(json, "\"schema_version\":4"));
    assert(strstr(json, "\"status\":\"activated\""));
    assert(strstr(json, "\"status\":\"evaluated\",\"evaluated\":true"));
    assert(strstr(json, "\"id0\":\"18364758544493064720\""));
    assert(strstr(json, "\"id1\":\"1311768467463790320\""));
    assert(strstr(json, "\"pts\":[12.5,25.75]"));
    assert(strstr(json, "12.5") && strstr(json, "25.75"));
    assert(strstr(json, "\"neighbors\":["));
    assert(strstr(json, "\"rejection\":0"));
    assert(strstr(json, "\"pixel_in_bounds\":true"));
    assert(strstr(json, "\"pixel_source\":[1352,224,1920,1080]"));
    assert(strstr(json, "\"map\":[480,270,1,0]"));
    assert(strstr(json, "\"source_width\":1920,\"source_height\":1080"));
    assert(strstr(json, "\"gpu_readback\":{\"status\":\"ready\",\"poll_count\":1}"));
    assert(strstr(json, "\"file_result\":\"success\""));
    assert(strstr(json, "null"));
    assert(mpvrx_jelly_probe_writer_error(&w) == 0);
    assert(mpvrx_jelly_probe_writer_join(&w) == 0);
    mpvrx_jelly_probe_writer_destroy(&w);
    unlink(path);
    puts("ok activation record, stage fields, IDs/PTS, NaN null, atomic report and safe join");

    char invalid_metadata_path[512];
    make_temp_path(invalid_metadata_path, sizeof invalid_metadata_path, "mpvrx-jelly-invalid-metadata");
    struct mpvrx_jelly_probe_writer invalid_metadata;
    assert(mpvrx_jelly_probe_writer_init(&invalid_metadata, invalid_metadata_path) == 0);
    assert(mpvrx_jelly_probe_writer_arm(&invalid_metadata) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    unsigned char invalid_record[MPVRX_JELLY_PROBE_RECORD_BYTES] = {0};
    float invalid_values[256] = {0};
    invalid_values[0] = 1.0f;
    invalid_values[1] = 5.0f;
    memcpy(invalid_record, invalid_values, sizeof invalid_values);
    assert(mpvrx_jelly_probe_writer_capture(&invalid_metadata, invalid_record, 1) == MPVRX_JELLY_CAPTURE_ACCEPTED);
    wait_for_status(&invalid_metadata, MPVRX_JELLY_PROBE_COMPLETE);
    read_report(invalid_metadata_path, json, sizeof json);
    assert(strstr(json, "\"status\":\"no_sample\""));
    assert(strstr(json, "\"reason\":\"invalid_probe_metadata\""));
    assert(strstr(json, "\"evaluation_marker_observed\":true"));
    assert(strstr(json, "\"gpu_readback\":{\"status\":\"ready\",\"poll_count\":1}"));
    assert(mpvrx_jelly_probe_writer_join(&invalid_metadata) == 0);
    mpvrx_jelly_probe_writer_destroy(&invalid_metadata);
    unlink(invalid_metadata_path);
    unsigned char invalid_field[MPVRX_JELLY_PROBE_RECORD_BYTES];
    memcpy(invalid_field, rec, sizeof invalid_field);
    memset(invalid_field + 58 * 16, 0, 16);
    assert_invalid_shader_record(invalid_field, "mpvrx-jelly-missing-ids");
    memcpy(invalid_field, rec, sizeof invalid_field);
    float invalid_pts = NAN;
    memcpy(invalid_field + 59 * 16, &invalid_pts, sizeof invalid_pts);
    assert_invalid_shader_record(invalid_field, "mpvrx-jelly-missing-pts");
    memcpy(invalid_field, rec, sizeof invalid_field);
    set_shader_float(invalid_field, 60, 2, 0.0f);
    assert_invalid_shader_record(invalid_field, "mpvrx-jelly-missing-dimensions");
    memcpy(invalid_field, rec, sizeof invalid_field);
    set_shader_float(invalid_field, 60, 0, 1920.0f);
    assert_invalid_shader_record(invalid_field, "mpvrx-jelly-out-of-bounds-pixel");
    memcpy(invalid_field, rec, sizeof invalid_field);
    set_shader_float(invalid_field, 61, 0, 0.0f);
    assert_invalid_shader_record(invalid_field, "mpvrx-jelly-missing-map");
    puts("ok invalid GPU metadata is not mislabeled as a Jelly rejection");

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
    assert(strstr(json, "\"schema_version\":5"));
    assert(strstr(json, "\"reason\":\"jelly_rejected_or_skipped\""));
    assert(strstr(json, "\"stage_snapshot\":\"provided\""));
    assert(strstr(json, "probe every eligible frame pair; PTS is recorded for context"));
    assert(strstr(json, "\"pts_window_seconds\":null"));
    assert(strstr(json, "\"sample_position_fraction\":[0.7041666667,0.2074074074]"));
    assert(strstr(json, "\"pixel\":[1352,224]"));
    assert(strstr(json, "\"eligible_count\":1"));
    assert(strstr(json, "\"pts_available_count\":1"));
    assert(strstr(json, "\"render_callbacks\":{\"status\":\"observed\",\"count\":1,\"while_armed\":1"));
    assert(strstr(json, "\"last_candidate\":{\"pts_valid\":true,\"pts\":[1.47,1.61]"));
    assert(strstr(json, "\"view_width\":null,\"view_height\":null"));
    assert(strstr(json, "\"pts\":[1.47,1.61]"));
    assert(strstr(json, "\"alpha\":0.5"));
    assert(strstr(json, "\"selection\":\"pts_available\""));
    assert(strstr(json, "\"width\":1920") && strstr(json, "\"height\":1080"));
    assert(strstr(json, "\"pixel_in_bounds\":true"));
    assert(strstr(json, "\"reason_code\":5"));
    assert(strstr(json, "\"poll_count\":2"));
    assert(strstr(json, "\"file_result\":\"success\""));
    assert(mpvrx_jelly_probe_writer_join(&no_sample) == 0);
    mpvrx_jelly_probe_writer_destroy(&no_sample);
    unlink(no_sample_path);
    puts("ok no-sample report preserves observed pair, bounds, Jelly rejection, poll and writer stages");

    char no_callback_path[512];
    make_temp_path(no_callback_path, sizeof(no_callback_path), "mpvrx-jelly-no-callback");
    struct mpvrx_jelly_probe_writer no_callback;
    assert(mpvrx_jelly_probe_writer_init(&no_callback, no_callback_path) == 0);
    struct mpvrx_jelly_probe_diagnostics callback_diagnostics = {0};
    callback_diagnostics.setup_status = MPVRX_JELLY_STAGE_OK;
    callback_diagnostics.writer_init_status = MPVRX_JELLY_STAGE_OK;
    callback_diagnostics.buffer_status = MPVRX_JELLY_STAGE_OK;
    callback_diagnostics.pixel_x = 1352;
    callback_diagnostics.pixel_y = 224;
    callback_diagnostics.gpu_last_poll_status = MPVRX_JELLY_READBACK_NOT_SUBMITTED;
    callback_diagnostics.pixel_evaluated = -1;
    callback_diagnostics.jelly_reason_code = -1;
    callback_diagnostics.activation = -1;
    callback_diagnostics.last_candidate_pts_valid = -1;
    callback_diagnostics.last_candidate_selection_mode = -1;
    mpvrx_jelly_probe_writer_finish(&no_callback, &callback_diagnostics);
    wait_for_status(&no_callback, MPVRX_JELLY_PROBE_NO_SAMPLE);
    read_report(no_callback_path, json, sizeof json);
    assert(strstr(json, "\"reason\":\"no_motion_render_callback\""));
    assert(strstr(json, "\"render_callbacks\":{\"status\":\"observed\",\"count\":0,\"while_armed\":0"));
    assert(strstr(json, "\"last_candidate\":null"));
    assert(mpvrx_jelly_probe_writer_join(&no_callback) == 0);
    mpvrx_jelly_probe_writer_destroy(&no_callback);
    unlink(no_callback_path);
    puts("ok no-sample report distinguishes a successful arm with no motion render callbacks");

    char unsupported_path[512];
    make_temp_path(unsupported_path, sizeof(unsupported_path), "mpvrx-jelly-views-unsupported");
    struct mpvrx_jelly_probe_writer unsupported;
    assert(mpvrx_jelly_probe_writer_init(&unsupported, unsupported_path) == 0);
    struct mpvrx_jelly_probe_diagnostics unsupported_diagnostics = {0};
    unsupported_diagnostics.setup_status = MPVRX_JELLY_STAGE_OK;
    unsupported_diagnostics.writer_init_status = MPVRX_JELLY_STAGE_OK;
    unsupported_diagnostics.buffer_status = MPVRX_JELLY_STAGE_OK;
    unsupported_diagnostics.motion_views_enabled = 1;
    unsupported_diagnostics.render_source_mix_callback_count = 24;
    unsupported_diagnostics.motion_view_pair_count = 4;
    unsupported_diagnostics.motion_view_unsupported_pair_count = 4;
    unsupported_diagnostics.pixel_x = 1352;
    unsupported_diagnostics.pixel_y = 224;
    unsupported_diagnostics.gpu_last_poll_status = MPVRX_JELLY_READBACK_NOT_SUBMITTED;
    unsupported_diagnostics.pixel_evaluated = -1;
    unsupported_diagnostics.jelly_reason_code = -1;
    unsupported_diagnostics.activation = -1;
    unsupported_diagnostics.last_candidate_pts_valid = 1;
    unsupported_diagnostics.last_candidate_pts[0] = 1.47;
    unsupported_diagnostics.last_candidate_pts[1] = 1.61;
    unsupported_diagnostics.last_candidate_alpha = 0.5;
    unsupported_diagnostics.last_candidate_selection_mode = 1;
    unsupported_diagnostics.last_candidate_source_width = 1920;
    unsupported_diagnostics.last_candidate_source_height = 1080;
    unsupported_diagnostics.last_candidate_view_width = -1;
    unsupported_diagnostics.last_candidate_view_height = -1;
    unsupported_diagnostics.last_candidate_pixel_in_bounds = 1;
    mpvrx_jelly_probe_writer_finish(&unsupported, &unsupported_diagnostics);
    wait_for_status(&unsupported, MPVRX_JELLY_PROBE_NO_SAMPLE);
    read_report(unsupported_path, json, sizeof json);
    assert(strstr(json, "\"reason\":\"motion_view_pair_unsupported\""));
    assert(strstr(json, "\"motion_view_path\":{\"status\":\"observed\",\"motion_views_enabled\":true"));
    assert(strstr(json, "\"render_source_mix_callbacks\":24,\"pair_count\":4,\"supported_pair_count\":0,\"unsupported_pair_count\":4"));
    assert(strstr(json, "\"shader_callback_count\":0,\"texture_callback_count\":0,\"probe_output_path\":\"offscreen_compute_only\",\"probe_dispatch_count\":0"));
    assert(mpvrx_jelly_probe_writer_join(&unsupported) == 0);
    mpvrx_jelly_probe_writer_destroy(&unsupported);
    unlink(unsupported_path);
    puts("ok no-sample report identifies selected motion-view pairs that the renderer cannot support");

    char dispatch_failure_path[512];
    make_temp_path(dispatch_failure_path, sizeof(dispatch_failure_path), "mpvrx-jelly-offscreen-dispatch-failure");
    struct mpvrx_jelly_probe_writer dispatch_failure;
    assert(mpvrx_jelly_probe_writer_init(&dispatch_failure, dispatch_failure_path) == 0);
    struct mpvrx_jelly_probe_diagnostics dispatch_failure_diagnostics = {0};
    dispatch_failure_diagnostics.setup_status = MPVRX_JELLY_STAGE_OK;
    dispatch_failure_diagnostics.writer_init_status = MPVRX_JELLY_STAGE_OK;
    dispatch_failure_diagnostics.buffer_status = MPVRX_JELLY_STAGE_OK;
    dispatch_failure_diagnostics.motion_render_callback_count = 1;
    dispatch_failure_diagnostics.armed_render_callback_count = 1;
    dispatch_failure_diagnostics.eligible_pair_count = 1;
    dispatch_failure_diagnostics.render_attempt_count = 1;
    dispatch_failure_diagnostics.render_failure_count = 1;
    dispatch_failure_diagnostics.gpu_last_poll_status = MPVRX_JELLY_READBACK_NOT_SUBMITTED;
    dispatch_failure_diagnostics.pixel_evaluated = -1;
    dispatch_failure_diagnostics.jelly_reason_code = -1;
    dispatch_failure_diagnostics.activation = -1;
    dispatch_failure_diagnostics.pixel_x = 1352;
    dispatch_failure_diagnostics.pixel_y = 224;
    dispatch_failure_diagnostics.source_bounds_observed = 1;
    dispatch_failure_diagnostics.source_width = 1920;
    dispatch_failure_diagnostics.source_height = 1080;
    dispatch_failure_diagnostics.pixel_in_bounds = 1;
    dispatch_failure_diagnostics.last_candidate_observed = 1;
    dispatch_failure_diagnostics.last_candidate_pts_valid = 1;
    dispatch_failure_diagnostics.last_candidate_pts[0] = 1.47;
    dispatch_failure_diagnostics.last_candidate_pts[1] = 1.61;
    dispatch_failure_diagnostics.last_candidate_alpha = 0.5;
    dispatch_failure_diagnostics.last_candidate_selection_mode = 1;
    dispatch_failure_diagnostics.last_candidate_source_width = 1920;
    dispatch_failure_diagnostics.last_candidate_source_height = 1080;
    dispatch_failure_diagnostics.last_candidate_view_width = 1920;
    dispatch_failure_diagnostics.last_candidate_view_height = 1080;
    dispatch_failure_diagnostics.last_candidate_pixel_in_bounds = 1;
    dispatch_failure_diagnostics.last_candidate_eligible = 1;
    mpvrx_jelly_probe_writer_finish(&dispatch_failure, &dispatch_failure_diagnostics);
    wait_for_status(&dispatch_failure, MPVRX_JELLY_PROBE_NO_SAMPLE);
    read_report(dispatch_failure_path, json, sizeof json);
    assert(strstr(json, "\"reason\":\"offscreen_probe_dispatch_failed\""));
    assert(mpvrx_jelly_probe_writer_join(&dispatch_failure) == 0);
    mpvrx_jelly_probe_writer_destroy(&dispatch_failure);
    unlink(dispatch_failure_path);
    puts("ok no-sample report identifies offscreen dispatch failure after an eligible pair");

    char alpha_path[512];
    make_temp_path(alpha_path, sizeof(alpha_path), "mpvrx-jelly-alpha-reject");
    struct mpvrx_jelly_probe_writer alpha_reject;
    assert(mpvrx_jelly_probe_writer_init(&alpha_reject, alpha_path) == 0);
    struct mpvrx_jelly_probe_diagnostics alpha_diagnostics = {0};
    alpha_diagnostics.setup_status = MPVRX_JELLY_STAGE_OK;
    alpha_diagnostics.writer_init_status = MPVRX_JELLY_STAGE_OK;
    alpha_diagnostics.buffer_status = MPVRX_JELLY_STAGE_OK;
    alpha_diagnostics.pixel_x = 1352;
    alpha_diagnostics.pixel_y = 224;
    alpha_diagnostics.motion_render_callback_count = 2;
    alpha_diagnostics.armed_render_callback_count = 2;
    alpha_diagnostics.alpha_below_count = 2;
    alpha_diagnostics.last_candidate_observed = 1;
    alpha_diagnostics.last_candidate_pts_valid = 1;
    alpha_diagnostics.last_candidate_pts[0] = 2.0;
    alpha_diagnostics.last_candidate_pts[1] = 2.1;
    alpha_diagnostics.last_candidate_alpha = 0.2;
    alpha_diagnostics.last_candidate_selection_mode = 2;
    alpha_diagnostics.last_candidate_source_width = 1920;
    alpha_diagnostics.last_candidate_source_height = 1080;
    alpha_diagnostics.last_candidate_pixel_in_bounds = 1;
    alpha_diagnostics.last_candidate_rejection_mask = 2;
    alpha_diagnostics.last_candidate_eligible = 0;
    alpha_diagnostics.gpu_last_poll_status = MPVRX_JELLY_READBACK_NOT_SUBMITTED;
    alpha_diagnostics.pixel_evaluated = -1;
    alpha_diagnostics.jelly_reason_code = -1;
    alpha_diagnostics.activation = -1;
    mpvrx_jelly_probe_writer_finish(&alpha_reject, &alpha_diagnostics);
    wait_for_status(&alpha_reject, MPVRX_JELLY_PROBE_NO_SAMPLE);
    read_report(alpha_path, json, sizeof json);
    assert(strstr(json, "\"reason\":\"alpha_outside_probe_range\""));
    assert(strstr(json, "\"alpha_below_range_count\":2"));
    assert(strstr(json, "\"last_candidate\":{\"pts_valid\":true,\"pts\":[2,2.1]"));
    assert(strstr(json, "\"eligible\":false,\"readback_pending\":false,\"rejection_mask\":2"));
    assert(mpvrx_jelly_probe_writer_join(&alpha_reject) == 0);
    mpvrx_jelly_probe_writer_destroy(&alpha_reject);
    unlink(alpha_path);
    puts("ok no-sample report identifies alpha gating and retains the rejected candidate");

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
    assert(strstr(json, "\"pixel\":[null,null]"));
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
