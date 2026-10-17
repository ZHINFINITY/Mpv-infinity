/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
#ifndef MPVFLOW_CAPTURE_IO_H
#define MPVFLOW_CAPTURE_IO_H

#include <stddef.h>
#include <stdint.h>

#define MPVFLOW_CAPTURE_MAX_PAIRS 8
#define MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR 4
#define MPVFLOW_CAPTURE_PAIRED_MAX_PAIRS 1
#define MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR 2
#define MPVFLOW_CAPTURE_MAX_BYTES (64u * 1024u * 1024u)
#define MPVFLOW_CAPTURE_IMAGE_MAX_DIMENSION 640
#define MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION 1920
#define MPVFLOW_CAPTURE_DECISION_MAX_PIXELS 2073600

enum MPVFlowCaptureBackendKind {
    MPVFLOW_CAPTURE_BACKEND_UNKNOWN = 0,
    MPVFLOW_CAPTURE_BACKEND_OPENCL,
    MPVFLOW_CAPTURE_BACKEND_VULKAN,
};

enum MPVFlowCaptureBackendKind mpvflow_capture_io_backend_kind(const char *backend);
const char *mpvflow_capture_io_backend_label(enum MPVFlowCaptureBackendKind kind);
int mpvflow_capture_io_backend_supports_vulkan_readback(const char *backend);
int mpvflow_capture_io_scaled_dimensions(int width, int height, int max_dimension,
                                         int *scaled_width, int *scaled_height);

struct MPVFlowCaptureSession;

struct MPVFlowCaptureMeta {
    const char *engine;
    const char *backend;
    int source_width;
    int source_height;
    int output_width;
    int output_height;
    int analysis_width;
    int analysis_height;
    int grid_width;
    int grid_height;
    double pts0;
    double pts1;
    double output_pts;
    float timestep;
    const char *effective_settings_json;
    int paired_output;
    int soft_blend_enabled;
    int visibility_resolver_enabled;
    int preserve_motion_blend_on_conflict;
};

struct MPVFlowCaptureSession *mpvflow_capture_io_start(
    const char *base_dir,
    const char *session_name,
    const struct MPVFlowCaptureMeta *initial_meta,
    char *reason,
    size_t reason_size);

int mpvflow_capture_io_write(
    struct MPVFlowCaptureSession *session,
    int pair_index,
    int sample_index,
    const char *channel,
    const struct MPVFlowCaptureMeta *meta,
    const uint8_t *rgba8,
    int width,
    int height,
    char *reason,
    size_t reason_size);

void mpvflow_capture_io_missing(
    struct MPVFlowCaptureSession *session,
    int pair_index,
    int sample_index,
    const char *channel,
    const char *stage,
    const char *reason);

/* Write a terminal, valid manifest when no renderable synthesized sample arrived. */
int mpvflow_capture_io_write_terminal_manifest(
    const char *base_dir,
    const char *session_name,
    const char *engine,
    const char *backend,
    const char *effective_settings_json,
    int paired_output,
    const char *reason);

int mpvflow_capture_io_stop(struct MPVFlowCaptureSession *session);
const char *mpvflow_capture_io_directory(const struct MPVFlowCaptureSession *session);
const char *mpvflow_capture_io_status(const struct MPVFlowCaptureSession *session);
void mpvflow_capture_io_destroy(struct MPVFlowCaptureSession *session);

/* Writes a tightly packed RGBA8 PNG atomically via a sibling .part file. */
int mpvflow_capture_write_png(const char *path, const uint8_t *rgba8,
                              uint32_t width, uint32_t height);

#endif
