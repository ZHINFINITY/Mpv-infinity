/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code.
 * See LICENSE for terms.
 */
#define _POSIX_C_SOURCE 200809L

#include "mpvflow_capture_io.h"

#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define MAX_CHANNEL_NAME 32
#define MAX_RELATIVE_PATH 96
#define MAX_MISSING_RECORDS 256
#define MAX_IMAGE_RAW_BYTES (8u * 1024u * 1024u)
#define MAX_SETTINGS_BYTES 4096

struct ImageRecord {
    int pair;
    int sample;
    int width;
    int height;
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
    int paired_output;
    int soft_blend_enabled;
    int visibility_resolver_enabled;
    int preserve_motion_blend_on_conflict;
    char channel[MAX_CHANNEL_NAME];
    char path[MAX_RELATIVE_PATH];
};

struct MissingRecord {
    int pair;
    int sample;
    char channel[MAX_CHANNEL_NAME];
    char stage[64];
    char reason[256];
};

struct MPVFlowCaptureSession {
    char directory[PATH_MAX];
    char status[16];
    char terminal_reason[96];
    char engine[24];
    char backend[16];
    char settings[MAX_SETTINGS_BYTES + 1];
    int paired_output;
    struct MPVFlowCaptureMeta initial_meta;
    struct ImageRecord images[384];
    struct MissingRecord missing[MAX_MISSING_RECORDS];
    size_t image_count;
    size_t missing_count;
    size_t bytes_written;
    unsigned pair_bits;
    unsigned sample_bits;
    int failed;
    int stopped;
};

struct JsonParser {
    const unsigned char *cursor;
    const unsigned char *end;
};

static void write_be32(uint8_t *out, uint32_t value)
{
    out[0] = (uint8_t) (value >> 24);
    out[1] = (uint8_t) (value >> 16);
    out[2] = (uint8_t) (value >> 8);
    out[3] = (uint8_t) value;
}

static uint32_t crc32_bytes(const uint8_t *bytes, size_t length)
{
    uint32_t crc = ~0u;
    for (size_t i = 0; i < length; i++) {
        crc ^= bytes[i];
        for (int bit = 0; bit < 8; bit++)
            crc = (crc >> 1) ^ (0xedb88320u & (uint32_t) -(int32_t) (crc & 1u));
    }
    return ~crc;
}

static int write_all(FILE *file, const void *data, size_t length)
{
    return fwrite(data, 1, length, file) == length ? 0 : -1;
}

static int write_png_chunk(FILE *file, const char type[4],
                           const uint8_t *data, uint32_t length)
{
#if SIZE_MAX < UINT32_MAX + 4ULL
    if ((size_t) length > SIZE_MAX - 4)
        return -1;
#endif
    const size_t crc_length = (size_t) length + 4;
    uint8_t *crc_data = malloc(crc_length);
    if (!crc_data)
        return -1;
    memcpy(crc_data, type, 4);
    if (length)
        memcpy(crc_data + 4, data, length);
    uint8_t size_bytes[4];
    uint8_t crc_bytes[4];
    write_be32(size_bytes, length);
    write_be32(crc_bytes, crc32_bytes(crc_data, crc_length));
    free(crc_data);
    if (write_all(file, size_bytes, sizeof(size_bytes)) ||
        write_all(file, type, 4) ||
        (length && write_all(file, data, length)) ||
        write_all(file, crc_bytes, sizeof(crc_bytes)))
        return -1;
    return 0;
}

int mpvflow_capture_write_png(const char *path, const uint8_t *rgba,
                              uint32_t width, uint32_t height)
{
    if (!path || !rgba || !width || !height || width > 32768 || height > 32768)
        return -1;
#if SIZE_MAX <= UINT32_MAX
    if (width > SIZE_MAX / 4)
        return -1;
#endif

    const size_t row_bytes = (size_t) width * 4;
    if (row_bytes == SIZE_MAX || (size_t) height > SIZE_MAX / (row_bytes + 1))
        return -1;
    const size_t raw_length = (row_bytes + 1) * (size_t) height;
    if (raw_length > MAX_IMAGE_RAW_BYTES)
        return -1;
    const size_t blocks = (raw_length + 65534) / 65535;
    if (raw_length > SIZE_MAX - 6 || blocks > (SIZE_MAX - 6 - raw_length) / 5)
        return -1;
    const size_t compressed_length = 6 + raw_length + blocks * 5;
    if (compressed_length > UINT32_MAX || raw_length > SIZE_MAX - 12)
        return -1;

    uint8_t *raw = malloc(raw_length);
    uint8_t *compressed = malloc(compressed_length);
    if (!raw || !compressed) {
        free(raw);
        free(compressed);
        return -1;
    }
    for (uint32_t y = 0; y < height; y++) {
        uint8_t *row = raw + (size_t) y * (row_bytes + 1);
        row[0] = 0; /* PNG filter: none */
        memcpy(row + 1, rgba + (size_t) y * row_bytes, row_bytes);
    }

    size_t compressed_used = 0;
    size_t position = 0;
    uint32_t adler_a = 1;
    uint32_t adler_b = 0;
    compressed[compressed_used++] = 0x78; /* zlib, deflate, fastest/no compression */
    compressed[compressed_used++] = 0x01;
    while (position < raw_length) {
        const size_t remaining = raw_length - position;
        const uint16_t block_length = (uint16_t) (remaining > 65535 ? 65535 : remaining);
        const uint16_t inverse_length = (uint16_t) ~block_length;
        compressed[compressed_used++] = (uint8_t) (position + block_length == raw_length);
        compressed[compressed_used++] = (uint8_t) block_length;
        compressed[compressed_used++] = (uint8_t) (block_length >> 8);
        compressed[compressed_used++] = (uint8_t) inverse_length;
        compressed[compressed_used++] = (uint8_t) (inverse_length >> 8);
        memcpy(compressed + compressed_used, raw + position, block_length);
        compressed_used += block_length;
        position += block_length;
    }
    for (size_t i = 0; i < raw_length; i++) {
        adler_a = (adler_a + raw[i]) % 65521;
        adler_b = (adler_b + adler_a) % 65521;
    }
    write_be32(compressed + compressed_used, (adler_b << 16) | adler_a);
    compressed_used += 4;
    free(raw);

    char temporary_path[PATH_MAX];
    if (snprintf(temporary_path, sizeof(temporary_path), "%s.part", path) >=
        (int) sizeof(temporary_path)) {
        free(compressed);
        return -1;
    }
    FILE *file = fopen(temporary_path, "wb");
    if (!file) {
        free(compressed);
        return -1;
    }

    const uint8_t signature[] = {137, 80, 78, 71, 13, 10, 26, 10};
    uint8_t header[13];
    write_be32(header, width);
    write_be32(header + 4, height);
    header[8] = 8;  /* bit depth */
    header[9] = 6;  /* RGBA */
    header[10] = header[11] = header[12] = 0;
    int failed = write_all(file, signature, sizeof(signature)) ||
                 write_png_chunk(file, "IHDR", header, sizeof(header)) ||
                 write_png_chunk(file, "IDAT", compressed, (uint32_t) compressed_used) ||
                 write_png_chunk(file, "IEND", NULL, 0);
    free(compressed);
    if (fflush(file))
        failed = 1;
    if (fclose(file))
        failed = 1;
    if (failed || rename(temporary_path, path)) {
        remove(temporary_path);
        return -1;
    }
    return 0;
}

static int safe_token(const char *text, size_t maximum_length)
{
    if (!text || !*text || strlen(text) > maximum_length || text[0] == '.')
        return 0;
    for (; *text; text++) {
        const char c = *text;
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
              (c >= '0' && c <= '9') || c == '-' || c == '_'))
            return 0;
    }
    return 1;
}

static void skip_space(struct JsonParser *parser)
{
    while (parser->cursor < parser->end &&
           (*parser->cursor == ' ' || *parser->cursor == '\t' ||
            *parser->cursor == '\r' || *parser->cursor == '\n'))
        parser->cursor++;
}

static int parse_json_value(struct JsonParser *parser, unsigned depth);

static int parse_json_string(struct JsonParser *parser)
{
    if (parser->cursor >= parser->end || *parser->cursor++ != '"')
        return 0;
    while (parser->cursor < parser->end) {
        const unsigned char c = *parser->cursor++;
        if (c == '"')
            return 1;
        if (c < 0x20)
            return 0;
        if (c != '\\')
            continue;
        if (parser->cursor >= parser->end)
            return 0;
        const unsigned char escaped = *parser->cursor++;
        if (escaped == '"' || escaped == '\\' || escaped == '/' ||
            escaped == 'b' || escaped == 'f' || escaped == 'n' ||
            escaped == 'r' || escaped == 't')
            continue;
        if (escaped != 'u' || (size_t) (parser->end - parser->cursor) < 4)
            return 0;
        for (int i = 0; i < 4; i++) {
            const unsigned char hex = parser->cursor[i];
            if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f') ||
                  (hex >= 'A' && hex <= 'F')))
                return 0;
        }
        parser->cursor += 4;
    }
    return 0;
}

static int parse_json_number(struct JsonParser *parser)
{
    const unsigned char *start = parser->cursor;
    if (parser->cursor < parser->end && *parser->cursor == '-')
        parser->cursor++;
    if (parser->cursor >= parser->end)
        return 0;
    if (*parser->cursor == '0') {
        parser->cursor++;
        if (parser->cursor < parser->end && *parser->cursor >= '0' && *parser->cursor <= '9')
            return 0;
    } else {
        if (*parser->cursor < '1' || *parser->cursor > '9')
            return 0;
        while (parser->cursor < parser->end && *parser->cursor >= '0' && *parser->cursor <= '9')
            parser->cursor++;
    }
    if (parser->cursor < parser->end && *parser->cursor == '.') {
        parser->cursor++;
        if (parser->cursor >= parser->end || *parser->cursor < '0' || *parser->cursor > '9')
            return 0;
        while (parser->cursor < parser->end && *parser->cursor >= '0' && *parser->cursor <= '9')
            parser->cursor++;
    }
    if (parser->cursor < parser->end && (*parser->cursor == 'e' || *parser->cursor == 'E')) {
        parser->cursor++;
        if (parser->cursor < parser->end && (*parser->cursor == '+' || *parser->cursor == '-'))
            parser->cursor++;
        if (parser->cursor >= parser->end || *parser->cursor < '0' || *parser->cursor > '9')
            return 0;
        while (parser->cursor < parser->end && *parser->cursor >= '0' && *parser->cursor <= '9')
            parser->cursor++;
    }
    return parser->cursor > start;
}

static int parse_json_array(struct JsonParser *parser, unsigned depth)
{
    parser->cursor++; /* '[' */
    skip_space(parser);
    if (parser->cursor < parser->end && *parser->cursor == ']') {
        parser->cursor++;
        return 1;
    }
    for (;;) {
        if (!parse_json_value(parser, depth + 1))
            return 0;
        skip_space(parser);
        if (parser->cursor >= parser->end)
            return 0;
        if (*parser->cursor == ']') {
            parser->cursor++;
            return 1;
        }
        if (*parser->cursor++ != ',')
            return 0;
        skip_space(parser);
    }
}

static int parse_json_object(struct JsonParser *parser, unsigned depth)
{
    parser->cursor++; /* '{' */
    skip_space(parser);
    if (parser->cursor < parser->end && *parser->cursor == '}') {
        parser->cursor++;
        return 1;
    }
    for (;;) {
        if (!parse_json_string(parser))
            return 0;
        skip_space(parser);
        if (parser->cursor >= parser->end || *parser->cursor++ != ':')
            return 0;
        skip_space(parser);
        if (!parse_json_value(parser, depth + 1))
            return 0;
        skip_space(parser);
        if (parser->cursor >= parser->end)
            return 0;
        if (*parser->cursor == '}') {
            parser->cursor++;
            return 1;
        }
        if (*parser->cursor++ != ',')
            return 0;
        skip_space(parser);
    }
}

static int parse_json_value(struct JsonParser *parser, unsigned depth)
{
    if (depth > 32 || parser->cursor >= parser->end)
        return 0;
    if (*parser->cursor == '"')
        return parse_json_string(parser);
    if (*parser->cursor == '{')
        return parse_json_object(parser, depth);
    if (*parser->cursor == '[')
        return parse_json_array(parser, depth);
    if (*parser->cursor == '-' || (*parser->cursor >= '0' && *parser->cursor <= '9'))
        return parse_json_number(parser);
    static const char *const literals[] = {"true", "false", "null"};
    for (size_t i = 0; i < sizeof(literals) / sizeof(literals[0]); i++) {
        const size_t length = strlen(literals[i]);
        if ((size_t) (parser->end - parser->cursor) >= length &&
            memcmp(parser->cursor, literals[i], length) == 0) {
            parser->cursor += length;
            return 1;
        }
    }
    return 0;
}

static int valid_settings_object(const char *json)
{
    if (!json || !*json || strlen(json) > MAX_SETTINGS_BYTES)
        return 0;
    struct JsonParser parser = {
        .cursor = (const unsigned char *) json,
        .end = (const unsigned char *) json + strlen(json),
    };
    skip_space(&parser);
    if (parser.cursor >= parser.end || *parser.cursor != '{' ||
        !parse_json_object(&parser, 0))
        return 0;
    skip_space(&parser);
    return parser.cursor == parser.end;
}

static int supported_engine(const char *engine)
{
    return engine && (!strcmp(engine, "mpv") || !strcmp(engine, "native-media3"));
}

int mpvflow_capture_io_scaled_dimensions(int width, int height, int max_dimension,
                                         int *scaled_width, int *scaled_height)
{
    if (width <= 0 || height <= 0 || max_dimension <= 0 ||
        !scaled_width || !scaled_height)
        return 0;

    const int largest = width > height ? width : height;
    if (largest <= max_dimension) {
        *scaled_width = width;
        *scaled_height = height;
        return 1;
    }

    const int64_t half = largest / 2;
    int64_t out_width = ((int64_t) width * max_dimension + half) / largest;
    int64_t out_height = ((int64_t) height * max_dimension + half) / largest;
    if (out_width < 1) out_width = 1;
    if (out_height < 1) out_height = 1;
    if (out_width > max_dimension || out_height > max_dimension)
        return 0;
    *scaled_width = (int) out_width;
    *scaled_height = (int) out_height;
    return 1;
}

enum MPVFlowCaptureBackendKind mpvflow_capture_io_backend_kind(const char *backend)
{
    if (!backend)
        return MPVFLOW_CAPTURE_BACKEND_UNKNOWN;
    if (!strcmp(backend, "vulkan") || !strcmp(backend, "vulkan-spirv"))
        return MPVFLOW_CAPTURE_BACKEND_VULKAN;
    if (!strcmp(backend, "opencl") || !strcmp(backend, "opencl-qcom"))
        return MPVFLOW_CAPTURE_BACKEND_OPENCL;
    return MPVFLOW_CAPTURE_BACKEND_UNKNOWN;
}

const char *mpvflow_capture_io_backend_label(enum MPVFlowCaptureBackendKind kind)
{
    switch (kind) {
    case MPVFLOW_CAPTURE_BACKEND_VULKAN: return "vulkan";
    case MPVFLOW_CAPTURE_BACKEND_OPENCL: return "opencl";
    case MPVFLOW_CAPTURE_BACKEND_UNKNOWN: break;
    }
    return "unknown";
}

int mpvflow_capture_io_backend_supports_vulkan_readback(const char *backend)
{
    return mpvflow_capture_io_backend_kind(backend) == MPVFLOW_CAPTURE_BACKEND_VULKAN;
}

static int supported_backend(const char *backend)
{
    return backend && (!strcmp(backend, "vulkan") || !strcmp(backend, "opencl"));
}

static int supported_terminal_backend(const char *backend)
{
    return supported_backend(backend) ||
           (backend && (!strcmp(backend, "unknown") || !strcmp(backend, "unavailable")));
}

static int valid_meta(const struct MPVFlowCaptureMeta *meta)
{
    return meta && supported_engine(meta->engine) && supported_backend(meta->backend) &&
           meta->source_width > 0 && meta->source_height > 0 &&
           meta->output_width > 0 && meta->output_height > 0 &&
           meta->analysis_width > 0 && meta->analysis_height > 0 &&
           meta->grid_width > 0 && meta->grid_height > 0 &&
           isfinite(meta->pts0) && isfinite(meta->pts1) && isfinite(meta->output_pts) &&
           isfinite(meta->timestep) && meta->timestep >= 0.0f && meta->timestep <= 1.0f &&
           (meta->paired_output == 0 || meta->paired_output == 1) &&
           (meta->soft_blend_enabled == 0 || meta->soft_blend_enabled == 1) &&
           (meta->visibility_resolver_enabled == 0 || meta->visibility_resolver_enabled == 1) &&
           (meta->preserve_motion_blend_on_conflict == 0 ||
            meta->preserve_motion_blend_on_conflict == 1) &&
           valid_settings_object(meta->effective_settings_json) &&
           (!meta->paired_output ||
            (!meta->visibility_resolver_enabled && meta->timestep == 0.5f &&
             meta->pts1 > meta->pts0 &&
             fabs(meta->output_pts - (meta->pts0 + (meta->pts1 - meta->pts0) * 0.5)) < 0.000001 &&
             !strstr(meta->effective_settings_json, "\"soft_blend\"")));
}

static void write_json_string(FILE *file, const char *value)
{
    const unsigned char *cursor = (const unsigned char *) (value ? value : "");
    fputc('"', file);
    for (; *cursor; cursor++) {
        if (*cursor == '"' || *cursor == '\\') {
            fputc('\\', file);
            fputc(*cursor, file);
        } else if (*cursor < 0x20) {
            fprintf(file, "\\u%04x", *cursor);
        } else {
            fputc(*cursor, file);
        }
    }
    fputc('"', file);
}

static unsigned bit_count(unsigned value)
{
    unsigned count = 0;
    while (value) {
        count += value & 1u;
        value >>= 1;
    }
    return count;
}

static int session_max_pairs(const struct MPVFlowCaptureSession *session)
{
    return session && session->paired_output
        ? MPVFLOW_CAPTURE_PAIRED_MAX_PAIRS : MPVFLOW_CAPTURE_MAX_PAIRS;
}

static int session_max_samples(const struct MPVFlowCaptureSession *session)
{
    return session && session->paired_output
        ? MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR
        : MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR;
}

static int write_manifest_json(FILE *file, const struct MPVFlowCaptureSession *session)
{
    if (fprintf(file,
                "{\"status\":\"%s\",\"stopped\":%s,\"terminal_reason\":",
                session->status, session->stopped ? "true" : "false") < 0)
        return -1;
    write_json_string(file, session->terminal_reason);
    fputs(",\"engine\":", file);
    write_json_string(file, session->engine);
    fputs(",\"backend\":", file);
    write_json_string(file, session->backend);
    if (fprintf(file,
                ",\"source_dimensions\":[%d,%d],\"output_dimensions\":[%d,%d],\"analysis_dimensions\":[%d,%d],\"grid_dimensions\":[%d,%d],"
            "\"pts\":{\"source0\":%.17g,\"source1\":%.17g,\"output\":%.17g,\"timestep\":%.9g},"
                "\"effective_settings\":%s,\"paired_output_diagnostic\":%s,"
            "\"pair_count\":%u,\"sample_count\":%u,"
            "\"bytes_written\":%zu,\"limits\":{\"pairs\":%d,\"samples_per_pair\":%d,\"bytes\":%u},"
            "\"image_policy\":{\"max_dimension\":%d,\"color_images\":\"%s\","
            "\"maps\":\"native motion-grid resolution; not full-resolution\"},"
            "\"units\":{\"motion_forward\":\"working-resolution pixels; HSV direction and magnitude visualization\","
            "\"motion_backward\":\"working-resolution pixels; HSV direction and magnitude visualization\","
            "\"motion\":\"working-resolution pixels; legacy alias\","
            "\"residual\":\"normalized SAD [0,1]\","
            "\"uniqueness\":\"normalized best-vs-second cost margin [0,1]\","
            "\"confidence\":\"pair-level reliability proxy [0,1] using the synthesis shader thresholds\","
            "\"cycle_consistency\":\"nearest motion-grid vector-cycle error in working-resolution pixels\","
            "\"cycle_error\":\"nearest motion-grid vector-cycle error in working-resolution pixels; legacy alias\","
            "\"visibility_support\":\"1-confidence low-support/occlusion-candidate proxy [0,1], not ground truth\"},"
            "\"reliability_thresholds\":{\"cycle_smoothstep\":[0.75,4.0],"
                "\"residual_smoothstep\":[0.025,0.22],\"uniqueness_smoothstep\":[0.08,0.30]}",
            session->initial_meta.source_width, session->initial_meta.source_height,
            session->initial_meta.output_width, session->initial_meta.output_height,
            session->initial_meta.analysis_width, session->initial_meta.analysis_height,
            session->initial_meta.grid_width, session->initial_meta.grid_height,
            session->initial_meta.pts0, session->initial_meta.pts1,
            session->initial_meta.output_pts, session->initial_meta.timestep,
            session->settings, session->paired_output ? "true" : "false",
            bit_count(session->pair_bits), bit_count(session->sample_bits),
                session->bytes_written, session_max_pairs(session),
                session_max_samples(session), MPVFLOW_CAPTURE_MAX_BYTES,
                session->paired_output ? MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION : MPVFLOW_CAPTURE_IMAGE_MAX_DIMENSION,
                session->paired_output ? "native-resolution RGBA8; no resampling" : "aspect-preserving RGBA8 downscale") < 0)
        return -1;

    if (session->paired_output)
        fputs(",\"paired_comparison\":{\"only_differing_setting\":\"soft_blend\","
              "\"same_source_textures\":true,\"same_analyzed_motion_field\":true,"
              "\"same_source_pts_output_pts_and_timestep\":true,"
              "\"visibility_resolver_disabled_for_both_variants\":true,"
              "\"preserve_motion_blend_on_conflict_must_match\":true,"
              "\"variant_order\":[\"soft_blend_off\",\"soft_blend_on\"],\"alpha\":0.5}", file);
    if (session->paired_output)
        fputs(",\"decision_capture\":{\"schema\":\"mpvflow-decision-mask-v1\",\"sample_index\":0,\"dimensions\":\"synthesized output dimensions\",\"channels\":{\"R\":\"branch_id 0-12\",\"G\":\"round(confidence*255)\",\"B\":\"conflict reason bitset\",\"A\":255},\"branches\":{\"0\":\"normal warped blend\",\"1\":\"scene-cut source endpoint\",\"2\":\"static bypass\",\"3\":\"lowconf source0\",\"4\":\"lowconf source1\",\"5\":\"conflict warp0\",\"6\":\"conflict warp1\",\"7\":\"conflict source0\",\"8\":\"conflict source1\",\"9\":\"zero-weight source0\",\"10\":\"zero-weight source1\",\"11\":\"lowconf source blend\",\"12\":\"conflict source blend\"},\"conflict_bits\":{\"1\":\"luma_delta\",\"2\":\"chroma_boundary\",\"4\":\"ambiguity\",\"8\":\"neighborhood_conflict\"},\"palette_rgb\":{\"0\":[0,180,0],\"1\":[128,128,128],\"2\":[0,80,255],\"3\":[255,0,0],\"4\":[255,128,0],\"5\":[0,255,255],\"6\":[255,255,0],\"7\":[255,0,255],\"8\":[160,0,255],\"9\":[128,64,0],\"10\":[255,255,255],\"11\":[0,128,255],\"12\":[255,192,64]},\"geometry\":\"output uv=(p+0.5)/output; analysis=clamp(uv*analysis-0.5,0,analysis-1); grid step=4, blockfine=4, coarse=8\"}", file);
    fputs(",\"records\":[", file);

    for (size_t i = 0; i < session->image_count; i++) {
        const struct ImageRecord *image = &session->images[i];
        if (i)
            fputc(',', file);
        fprintf(file,
                "{\"pair_index\":%d,\"sample_index\":%d,\"channel\":",
                image->pair, image->sample);
        write_json_string(file, image->channel);
        fputs(",\"path\":", file);
        write_json_string(file, image->path);
        fprintf(file,
                ",\"width\":%d,\"height\":%d,\"source_dimensions\":[%d,%d],"
                "\"output_dimensions\":[%d,%d],\"analysis_dimensions\":[%d,%d],\"grid_dimensions\":[%d,%d],\"pts\":{\"source0\":%.17g,"
                "\"source1\":%.17g,\"output\":%.17g,\"timestep\":%.9g}",
                image->width, image->height, image->source_width, image->source_height,
                image->output_width, image->output_height, image->analysis_width, image->analysis_height,
                image->grid_width, image->grid_height, image->pts0, image->pts1,
                image->output_pts, image->timestep);
        if (session->paired_output && !strcmp(image->channel, "synthesized"))
            fprintf(file,
                    ",\"variant\":\"soft_blend_%s\",\"soft_blend\":%s,"
                    "\"visibility_resolver\":%s,"
                    "\"preserve_motion_blend_on_conflict\":%s",
                    image->soft_blend_enabled ? "on" : "off",
                    image->soft_blend_enabled ? "true" : "false",
                    image->visibility_resolver_enabled ? "true" : "false",
                    image->preserve_motion_blend_on_conflict ? "true" : "false");
        fputc('}', file);
    }
    fputs("],\"missing\":[", file);
    for (size_t i = 0; i < session->missing_count; i++) {
        const struct MissingRecord *missing = &session->missing[i];
        if (i)
            fputc(',', file);
        fprintf(file,
                "{\"pair_index\":%d,\"sample_index\":%d,\"channel\":",
                missing->pair, missing->sample);
        write_json_string(file, missing->channel);
        fputs(",\"stage\":", file);
        write_json_string(file, missing->stage);
        fputs(",\"reason\":", file);
        write_json_string(file, missing->reason);
        fputc('}', file);
    }
    fputs("]}", file);
    return ferror(file) ? -1 : 0;
}

static int write_manifest(struct MPVFlowCaptureSession *session)
{
    char final_path[PATH_MAX];
    char temporary_path[PATH_MAX];
    if (snprintf(final_path, sizeof(final_path), "%s/manifest.json", session->directory) >=
            (int) sizeof(final_path) ||
        snprintf(temporary_path, sizeof(temporary_path), "%s/manifest.json.part", session->directory) >=
            (int) sizeof(temporary_path))
        return -1;
    FILE *file = fopen(temporary_path, "wb");
    if (!file)
        return -1;
    int failed = write_manifest_json(file, session);
    if (fflush(file))
        failed = -1;
    if (fclose(file))
        failed = -1;
    if (failed || rename(temporary_path, final_path)) {
        remove(temporary_path);
        return -1;
    }
    return 0;
}

static int prepare_directory(const char *base_dir, const char *session_name,
                             char *directory, size_t directory_size,
                             char *reason, size_t reason_size)
{
    struct stat status;
    if (!base_dir || stat(base_dir, &status) || !S_ISDIR(status.st_mode) ||
        !safe_token(session_name, 80)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "invalid base directory or session name");
        return -1;
    }
    if (snprintf(directory, directory_size, "%s/%s", base_dir, session_name) >=
        (int) directory_size) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "session path too long");
        return -1;
    }
    if (mkdir(directory, 0700)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "cannot create session directory");
        return -1;
    }
    return 0;
}

struct MPVFlowCaptureSession *mpvflow_capture_io_start(
    const char *base_dir, const char *session_name,
    const struct MPVFlowCaptureMeta *initial_meta,
    char *reason, size_t reason_size)
{
    if (!valid_meta(initial_meta)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "invalid initial capture metadata");
        return NULL;
    }
    struct MPVFlowCaptureSession *session = calloc(1, sizeof(*session));
    if (!session)
        return NULL;
    if (prepare_directory(base_dir, session_name, session->directory,
                          sizeof(session->directory), reason, reason_size)) {
        free(session);
        return NULL;
    }
    snprintf(session->status, sizeof(session->status), "capturing");
    snprintf(session->engine, sizeof(session->engine), "%s", initial_meta->engine);
    snprintf(session->backend, sizeof(session->backend), "%s", initial_meta->backend);
    snprintf(session->settings, sizeof(session->settings), "%s",
             initial_meta->effective_settings_json);
    session->paired_output = initial_meta->paired_output;
    session->initial_meta = *initial_meta;
    session->initial_meta.engine = session->engine;
    session->initial_meta.backend = session->backend;
    session->initial_meta.effective_settings_json = session->settings;
    if (write_manifest(session)) {
        session->failed = 1;
        snprintf(session->terminal_reason, sizeof(session->terminal_reason), "manifest_write_failed");
        free(session);
        return NULL;
    }
    return session;
}

static int is_pair_channel(const char *channel)
{
    static const char *const channels[] = {
        "decision_raw", "decision_mask", "source0", "source1", "motion_forward", "motion_backward", "motion",
        "residual", "uniqueness", "confidence", "cycle_consistency", "cycle_error",
        "visibility_support",
    };
    for (size_t i = 0; i < sizeof(channels) / sizeof(channels[0]); i++)
        if (!strcmp(channel, channels[i]))
            return 1;
    return 0;
}

static int estimate_png_file_size(int width, int height, size_t *estimate)
{
    if (width <= 0 || height <= 0 || (size_t) width > SIZE_MAX / 4)
        return -1;
    const size_t row_bytes = (size_t) width * 4;
    if (row_bytes == SIZE_MAX || (size_t) height > SIZE_MAX / (row_bytes + 1))
        return -1;
    const size_t raw_length = (row_bytes + 1) * (size_t) height;
    if (raw_length > MAX_IMAGE_RAW_BYTES)
        return -1;
    const size_t blocks = (raw_length + 65534) / 65535;
    if (blocks > (SIZE_MAX - 57 - raw_length) / 5)
        return -1;
    *estimate = raw_length + blocks * 5 + 57;
    return 0;
}

int mpvflow_capture_io_write(
    struct MPVFlowCaptureSession *session, int pair_index, int sample_index,
    const char *channel, const struct MPVFlowCaptureMeta *meta,
    const uint8_t *rgba8, int width, int height, char *reason, size_t reason_size)
{
    if (!session || session->stopped || pair_index < 0 ||
        pair_index >= session_max_pairs(session) || !channel ||
        strlen(channel) >= MAX_CHANNEL_NAME || !safe_token(channel, MAX_CHANNEL_NAME - 1) ||
        !valid_meta(meta) || strcmp(meta->engine, session->engine) ||
        strcmp(meta->backend, session->backend) || !rgba8 || width <= 0 || height <= 0 ||
        (is_pair_channel(channel) ?
         ((!strcmp(channel, "decision_raw") || !strcmp(channel, "decision_mask")) ? sample_index != 0 : sample_index != -1) :
         (strcmp(channel, "synthesized") || sample_index < 0 || sample_index >= session_max_samples(session))) ||
        ((!strcmp(channel, "decision_raw") || !strcmp(channel, "decision_mask")) &&
         (!meta->paired_output || meta->soft_blend_enabled ||
          width != meta->output_width || height != meta->output_height ||
          width > MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION || height > MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION ||
          (int64_t) width * height > MPVFLOW_CAPTURE_DECISION_MAX_PIXELS)) ||
        (meta->paired_output &&
         ((!strcmp(channel, "synthesized") &&
           (width != meta->output_width || height != meta->output_height)) ||
          ((!strcmp(channel, "source0") || !strcmp(channel, "source1")) &&
           (width != meta->source_width || height != meta->source_height)))) ||
        meta->paired_output != session->paired_output ||
        (session->paired_output &&
         (strcmp(meta->effective_settings_json, session->settings) ||
          (!strcmp(channel, "synthesized") &&
           (meta->soft_blend_enabled != sample_index ||
            meta->visibility_resolver_enabled))))) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "invalid pair/sample/channel/dimensions/metadata");
        return -1;
    }
    if (session->paired_output && !strcmp(channel, "synthesized") && sample_index == 1) {
        const struct ImageRecord *off = NULL;
        for (size_t i = 0; i < session->image_count; i++) {
            const struct ImageRecord *candidate = &session->images[i];
            if (candidate->pair == pair_index && candidate->sample == 0 &&
                !strcmp(candidate->channel, "synthesized")) {
                off = candidate;
                break;
            }
        }
        if (!off || off->pts0 != meta->pts0 || off->pts1 != meta->pts1 ||
            off->output_pts != meta->output_pts || off->timestep != meta->timestep ||
            off->source_width != meta->source_width || off->source_height != meta->source_height ||
            off->output_width != meta->output_width || off->output_height != meta->output_height ||
            off->analysis_width != meta->analysis_width || off->analysis_height != meta->analysis_height ||
            off->grid_width != meta->grid_width || off->grid_height != meta->grid_height ||
            off->visibility_resolver_enabled != meta->visibility_resolver_enabled ||
            off->preserve_motion_blend_on_conflict != meta->preserve_motion_blend_on_conflict) {
            if (reason && reason_size)
                snprintf(reason, reason_size,
                         "paired variants differ beyond soft_blend or lack the off variant");
            return -1;
        }
    }
    if (session->image_count >= sizeof(session->images) / sizeof(session->images[0])) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "image record limit reached");
        session->failed = 1;
        return -1;
    }
    for (size_t i = 0; i < session->image_count; i++) {
        const struct ImageRecord *image = &session->images[i];
        if (image->pair == pair_index && image->sample == sample_index &&
            !strcmp(image->channel, channel)) {
            if (reason && reason_size)
                snprintf(reason, reason_size, "duplicate channel");
            return -1;
        }
    }

    char relative_path[MAX_RELATIVE_PATH];
    char full_path[PATH_MAX];
    if (snprintf(relative_path, sizeof(relative_path), "pair_%d_sample_%d_%s.png",
                 pair_index, sample_index, channel) >= (int) sizeof(relative_path) ||
        snprintf(full_path, sizeof(full_path), "%s/%s", session->directory,
                 relative_path) >= (int) sizeof(full_path)) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "capture image path too long");
        return -1;
    }
    size_t estimate = 0;
    if (estimate_png_file_size(width, height, &estimate) ||
        estimate > MPVFLOW_CAPTURE_MAX_BYTES - session->bytes_written) {
        if (reason && reason_size)
            snprintf(reason, reason_size, "capture byte limit exceeded before PNG write");
        session->failed = 1;
        return -1;
    }
    if (mpvflow_capture_write_png(full_path, rgba8, (uint32_t) width, (uint32_t) height)) {
        session->failed = 1;
        if (reason && reason_size)
            snprintf(reason, reason_size, "PNG write failed");
        return -1;
    }
    struct stat file_status;
    if (stat(full_path, &file_status) || file_status.st_size < 0 ||
        (uint64_t) file_status.st_size > MPVFLOW_CAPTURE_MAX_BYTES - session->bytes_written) {
        remove(full_path);
        session->failed = 1;
        if (reason && reason_size)
            snprintf(reason, reason_size, "capture byte limit exceeded");
        write_manifest(session);
        return -1;
    }

    struct ImageRecord *image = &session->images[session->image_count++];
    image->pair = pair_index;
    image->sample = sample_index;
    image->width = width;
    image->height = height;
    image->source_width = meta->source_width;
    image->source_height = meta->source_height;
    image->output_width = meta->output_width;
    image->output_height = meta->output_height;
    image->analysis_width = meta->analysis_width;
    image->analysis_height = meta->analysis_height;
    image->grid_width = meta->grid_width;
    image->grid_height = meta->grid_height;
    image->pts0 = meta->pts0;
    image->pts1 = meta->pts1;
    image->output_pts = meta->output_pts;
    image->timestep = meta->timestep;
    image->paired_output = meta->paired_output;
    image->soft_blend_enabled = meta->soft_blend_enabled;
    image->visibility_resolver_enabled = meta->visibility_resolver_enabled;
    image->preserve_motion_blend_on_conflict = meta->preserve_motion_blend_on_conflict;
    snprintf(image->channel, sizeof(image->channel), "%s", channel);
    snprintf(image->path, sizeof(image->path), "%s", relative_path);
    session->bytes_written += (size_t) file_status.st_size;
    session->pair_bits |= 1u << pair_index;
    if (sample_index >= 0 && !strcmp(channel, "synthesized"))
        session->sample_bits |= 1u << (pair_index * MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR + sample_index);
    if (write_manifest(session)) {
        session->failed = 1;
        if (reason && reason_size)
            snprintf(reason, reason_size, "manifest write failed after image commit");
        return -1;
    }
    return 0;
}

void mpvflow_capture_io_missing(struct MPVFlowCaptureSession *session,
                                int pair_index, int sample_index,
                                const char *channel, const char *stage,
                                const char *reason)
{
    if (!session || pair_index < 0 || pair_index >= session_max_pairs(session) ||
        sample_index < -1 || sample_index >= session_max_samples(session) ||
        !channel || !stage)
        return;
    if (session->missing_count >= MAX_MISSING_RECORDS) {
        session->failed = 1;
        snprintf(session->terminal_reason, sizeof(session->terminal_reason), "missing_record_limit_reached");
        write_manifest(session);
        return;
    }
    struct MissingRecord *record = &session->missing[session->missing_count++];
    record->pair = pair_index;
    record->sample = sample_index;
    snprintf(record->channel, sizeof(record->channel), "%s", channel);
    snprintf(record->stage, sizeof(record->stage), "%s", stage);
    snprintf(record->reason, sizeof(record->reason), "%s", reason ? reason : "");
    session->failed = 1;
    write_manifest(session);
}

int mpvflow_capture_io_write_terminal_manifest(
    const char *base_dir, const char *session_name, const char *engine,
    const char *backend, const char *effective_settings_json,
    int paired_output, const char *reason)
{
    if (!supported_engine(engine) || !supported_terminal_backend(backend) ||
        (paired_output != 0 && paired_output != 1) ||
        !valid_settings_object(effective_settings_json) ||
        (paired_output && strstr(effective_settings_json, "\"soft_blend\"")))
        return -1;
    char directory[PATH_MAX];
    char why[128];
    if (prepare_directory(base_dir, session_name, directory, sizeof(directory), why, sizeof(why)))
        return -1;
    char manifest_path[PATH_MAX];
    char temporary_path[PATH_MAX];
    if (snprintf(manifest_path, sizeof(manifest_path), "%s/manifest.json", directory) >=
            (int) sizeof(manifest_path) ||
        snprintf(temporary_path, sizeof(temporary_path), "%s/manifest.json.part", directory) >=
            (int) sizeof(temporary_path))
        return -1;
    FILE *file = fopen(temporary_path, "wb");
    if (!file)
        return -1;
    fputs("{\"status\":\"failed\",\"stopped\":true,\"terminal_reason\":", file);
    write_json_string(file, reason && *reason ? reason : "no_synthesized_samples");
    fputs(",\"engine\":", file);
    write_json_string(file, engine);
    fputs(",\"backend\":", file);
    write_json_string(file, backend);
    fprintf(file,
            ",\"source_dimensions\":null,\"output_dimensions\":null,\"pts\":null,"
            "\"effective_settings\":%s,\"paired_output_diagnostic\":%s,"
            "\"paired_comparison\":%s,"
            "\"pair_count\":0,\"sample_count\":0,"
            "\"bytes_written\":0,\"limits\":{\"pairs\":%d,\"samples_per_pair\":%d,\"bytes\":%u},"
            "\"image_policy\":{\"max_dimension\":%d,\"color_images\":\"%s\","
            "\"maps\":\"native motion-grid resolution; not full-resolution\"},"
            "\"units\":{\"motion_forward\":\"working-resolution pixels; HSV direction and magnitude visualization\","
            "\"motion_backward\":\"working-resolution pixels; HSV direction and magnitude visualization\","
            "\"residual\":\"normalized SAD [0,1]\",\"uniqueness\":\"normalized cost margin [0,1]\","
            "\"confidence\":\"pair-level reliability proxy [0,1]\","
            "\"cycle_consistency\":\"nearest-grid vector-cycle error in working-resolution pixels\","
            "\"visibility_support\":\"low-support/occlusion-candidate proxy, not ground truth\"},"
            "\"reliability_thresholds\":{\"cycle_smoothstep\":[0.75,4.0],"
            "\"residual_smoothstep\":[0.025,0.22],\"uniqueness_smoothstep\":[0.08,0.30]},"
            "\"records\":[],\"missing\":[{\"pair_index\":0,\"sample_index\":-1,"
            "\"channel\":\"synthesized\",\"stage\":\"capture\",\"reason\":",
            effective_settings_json, paired_output ? "true" : "false",
            paired_output
                ? "{\"only_differing_setting\":\"soft_blend\",\"same_source_textures\":true,"
                  "\"same_analyzed_motion_field\":true,\"same_source_pts_output_pts_and_timestep\":true,"
                  "\"visibility_resolver_disabled_for_both_variants\":true,"
                  "\"preserve_motion_blend_on_conflict_must_match\":true,"
                  "\"variant_order\":[\"soft_blend_off\",\"soft_blend_on\"],\"alpha\":0.5}"
                : "null",
            paired_output ? MPVFLOW_CAPTURE_PAIRED_MAX_PAIRS : MPVFLOW_CAPTURE_MAX_PAIRS,
            paired_output ? MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR : MPVFLOW_CAPTURE_MAX_SAMPLES_PER_PAIR,
            MPVFLOW_CAPTURE_MAX_BYTES,
            paired_output ? MPVFLOW_CAPTURE_DECISION_MAX_DIMENSION : MPVFLOW_CAPTURE_IMAGE_MAX_DIMENSION,
            paired_output ? "native-resolution RGBA8; no resampling" : "aspect-preserving RGBA8 downscale");
    write_json_string(file, reason && *reason ? reason : "no_synthesized_samples");
    fputs("}]}", file);
    int failed = ferror(file);
    if (fflush(file))
        failed = 1;
    if (fclose(file))
        failed = 1;
    if (failed || rename(temporary_path, manifest_path)) {
        remove(temporary_path);
        return -1;
    }
    return 0;
}

int mpvflow_capture_io_stop(struct MPVFlowCaptureSession *session)
{
    if (!session)
        return -1;
    session->stopped = 1;
    if (session->paired_output && session->sample_bits) {
        unsigned required = 0;
        for (size_t i = 0; i < session->image_count; i++) {
            const struct ImageRecord *image = &session->images[i];
            if (!strcmp(image->channel, "source0")) required |= 1u;
            if (!strcmp(image->channel, "source1")) required |= 2u;
            if (!strcmp(image->channel, "decision_raw")) required |= 4u;
            if (!strcmp(image->channel, "decision_mask")) required |= 8u;
            if (!strcmp(image->channel, "synthesized")) required |= 16u << image->sample;
        }
        if (required != 63u) {
            session->failed = 1;
            snprintf(session->terminal_reason, sizeof(session->terminal_reason),
                     "fixed_pair_proof_channel_missing");
        }
    }
    if (!session->sample_bits) {
        snprintf(session->status, sizeof(session->status), "failed");
        snprintf(session->terminal_reason, sizeof(session->terminal_reason), "no_synthesized_samples");
    } else if (session->failed) {
        snprintf(session->status, sizeof(session->status), "partial");
        if (!session->terminal_reason[0])
            snprintf(session->terminal_reason, sizeof(session->terminal_reason), "diagnostic_channel_missing_or_write_failed");
    } else {
        snprintf(session->status, sizeof(session->status), "complete");
        session->terminal_reason[0] = '\0';
    }
    const int manifest_failed = write_manifest(session);
    return !manifest_failed && !strcmp(session->status, "complete") ? 0 : -1;
}

const char *mpvflow_capture_io_directory(const struct MPVFlowCaptureSession *session)
{
    return session ? session->directory : NULL;
}

const char *mpvflow_capture_io_status(const struct MPVFlowCaptureSession *session)
{
    return session ? session->status : NULL;
}

void mpvflow_capture_io_destroy(struct MPVFlowCaptureSession *session)
{
    free(session);
}
