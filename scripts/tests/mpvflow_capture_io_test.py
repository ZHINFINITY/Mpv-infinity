#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
import json
import pathlib
import struct
import subprocess
import tempfile
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/cpp/mpvflow/mpvflow_capture_io.c"

C_FIXTURE = r'''#include "mpvflow_capture_io.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static struct MPVFlowCaptureMeta meta = {
    "mpv", "vulkan", 1920, 1080, 1920, 1080, 1920, 1080, 480, 270,
    10.25, 11.5, 10.75, .5, "{\"x\":\"q\\\"\\n\"}", 0, 0, 0, 0
};

int main(int argc, char **argv) {
    (void) argc;
    uint8_t px[16] = {
        255,0,0,255, 0,255,0,128,
        0,0,255,0, 17,34,51,255
    };
    char reason[256];
    int source_tile_width = 0, source_tile_height = 0;
    int output_tile_width = 0, output_tile_height = 0;
    assert(mpvflow_capture_io_scaled_dimensions(512, 288,
        MPVFLOW_CAPTURE_IMAGE_MAX_DIMENSION, &source_tile_width, &source_tile_height));
    assert(source_tile_width == 512 && source_tile_height == 288);
    assert(mpvflow_capture_io_scaled_dimensions(1920, 1080,
        MPVFLOW_CAPTURE_IMAGE_MAX_DIMENSION, &output_tile_width, &output_tile_height));
    assert(output_tile_width == 640 && output_tile_height == 360);
    assert(!mpvflow_capture_io_scaled_dimensions(0, 1080,
        MPVFLOW_CAPTURE_IMAGE_MAX_DIMENSION, &output_tile_width, &output_tile_height));
    assert(mpvflow_capture_io_backend_kind("vulkan-spirv") == MPVFLOW_CAPTURE_BACKEND_VULKAN);
    assert(mpvflow_capture_io_backend_supports_vulkan_readback("vulkan-spirv"));
    assert(mpvflow_capture_io_backend_kind("opencl-qcom") == MPVFLOW_CAPTURE_BACKEND_OPENCL);
    assert(!mpvflow_capture_io_backend_supports_vulkan_readback("opencl-qcom"));
    assert(mpvflow_capture_io_backend_kind("unknown") == MPVFLOW_CAPTURE_BACKEND_UNKNOWN);
    assert(!strcmp(mpvflow_capture_io_backend_label(MPVFLOW_CAPTURE_BACKEND_VULKAN), "vulkan"));
    assert(!strcmp(mpvflow_capture_io_backend_label(MPVFLOW_CAPTURE_BACKEND_OPENCL), "opencl"));
    assert(!mpvflow_capture_write_png(argv[1], px, 2, 2));
    struct MPVFlowCaptureSession *session = mpvflow_capture_io_start(
        argv[2], "valid", &meta, reason, sizeof(reason));
    assert(session);

    for (int pair = 0; pair < 8; pair++) {
        for (int sample = 0; sample < 4; sample++)
            assert(!mpvflow_capture_io_write(session, pair, sample, "synthesized",
                &meta, px, 2, 2, reason, sizeof(reason)));
    }
    assert(mpvflow_capture_io_write(session, 0, 0, "synthesized",
        &meta, px, 2, 2, reason, sizeof(reason)));
    assert(mpvflow_capture_io_write(session, 8, 0, "synthesized",
        &meta, px, 2, 2, reason, sizeof(reason)));
    assert(!mpvflow_capture_io_write(session, 0, -1, "source0",
        &meta, px, 2, 2, reason, sizeof(reason)));
    assert(!mpvflow_capture_io_write(session, 0, -1, "source1",
        &meta, px, 2, 2, reason, sizeof(reason)));
    const char *maps[] = {
        "motion_forward", "motion_backward", "residual", "uniqueness",
        "confidence", "cycle_consistency", "visibility_support"
    };
    for (size_t i = 0; i < sizeof(maps) / sizeof(maps[0]); i++)
        assert(!mpvflow_capture_io_write(session, 0, -1, maps[i],
            &meta, px, 2, 2, reason, sizeof(reason)));
    mpvflow_capture_io_missing(session, 2, 3, "confidence", "map_dispatch",
                               "bad \"quote\"\nline");
    assert(mpvflow_capture_io_stop(session));
    mpvflow_capture_io_destroy(session);

    meta.engine = "native-media3";
    session = mpvflow_capture_io_start(argv[2], "media3", &meta, reason, sizeof(reason));
    assert(session);
    assert(!mpvflow_capture_io_write(session, 0, -1, "source0",
        &meta, px, 2, 2, reason, sizeof(reason)));
    assert(mpvflow_capture_io_stop(session));
    mpvflow_capture_io_destroy(session);

    struct MPVFlowCaptureMeta paired_meta = {
        "mpv", "vulkan", 2, 2, 2, 2, 640, 360, 160, 90,
        20.0, 20.04, 20.02, .5,
        "{\"visibility_resolver\":false,\"preserve_motion_blend_on_conflict\":true}",
        1, 0, 0, 1
    };
    struct MPVFlowCaptureMeta invalid_paired_meta = paired_meta;
    invalid_paired_meta.effective_settings_json =
        "{\"visibility_resolver\":false,\"soft_blend\":false}";
    assert(!mpvflow_capture_io_start(argv[2], "paired_bad_settings",
        &invalid_paired_meta, reason, sizeof(reason)));
    invalid_paired_meta = paired_meta;
    invalid_paired_meta.visibility_resolver_enabled = 1;
    assert(!mpvflow_capture_io_start(argv[2], "paired_resolver_on",
        &invalid_paired_meta, reason, sizeof(reason)));
    invalid_paired_meta = paired_meta;
    invalid_paired_meta.timestep = .25f;
    assert(!mpvflow_capture_io_start(argv[2], "paired_non_midpoint",
        &invalid_paired_meta, reason, sizeof(reason)));
    session = mpvflow_capture_io_start(argv[2], "paired_missing_masks", &paired_meta,
                                       reason, sizeof(reason));
    assert(session);
    assert(!mpvflow_capture_io_write(session, 0, 0, "synthesized",
        &paired_meta, px, 2, 2, reason, sizeof(reason)));
    assert(mpvflow_capture_io_stop(session));
    assert(!strcmp(mpvflow_capture_io_status(session), "partial"));
    mpvflow_capture_io_destroy(session);
    session = mpvflow_capture_io_start(argv[2], "paired", &paired_meta,
                                       reason, sizeof(reason));
    assert(session);
    assert(!mpvflow_capture_io_write(session, 0, -1, "source0",
        &paired_meta, px, 2, 2, reason, sizeof(reason)));
    assert(!mpvflow_capture_io_write(session, 0, -1, "source1",
        &paired_meta, px, 2, 2, reason, sizeof(reason)));
    uint8_t *decision_pixels = calloc(2u * 2u, 4);
    assert(decision_pixels);
    assert(!mpvflow_capture_io_write(session, 0, 0, "decision_raw",
        &paired_meta, decision_pixels, 2, 2, reason, sizeof(reason)));
    assert(!mpvflow_capture_io_write(session, 0, 0, "decision_mask",
        &paired_meta, decision_pixels, 2, 2, reason, sizeof(reason)));
    free(decision_pixels);
    assert(mpvflow_capture_io_write(session, 0, 1, "decision_raw",
        &paired_meta, px, 2, 2, reason, sizeof(reason)));
    assert(!mpvflow_capture_io_write(session, 0, 0, "synthesized",
        &paired_meta, px, 2, 2, reason, sizeof(reason)));
    paired_meta.soft_blend_enabled = 1;
    const int paired_on_result = mpvflow_capture_io_write(session, 0, 1, "synthesized",
        &paired_meta, px, 2, 2, reason, sizeof(reason));
    if (paired_on_result) fprintf(stderr, "paired on variant rejected: %s\n", reason);
    assert(!paired_on_result);
    struct MPVFlowCaptureMeta mismatched_variant = paired_meta;
    mismatched_variant.pts0 += 0.001;
    assert(mpvflow_capture_io_write(session, 0, 1, "synthesized",
        &mismatched_variant, px, 2, 2, reason, sizeof(reason)));
    mismatched_variant = paired_meta;
    mismatched_variant.preserve_motion_blend_on_conflict = 0;
    assert(mpvflow_capture_io_write(session, 0, 1, "synthesized",
        &mismatched_variant, px, 2, 2, reason, sizeof(reason)));
    assert(mpvflow_capture_io_write(session, 2, 0, "synthesized",
        &paired_meta, px, 2, 2, reason, sizeof(reason)));
    assert(mpvflow_capture_io_write(session, 0, 2, "synthesized",
        &paired_meta, px, 2, 2, reason, sizeof(reason)));
    assert(mpvflow_capture_io_stop(session) == 0);
    mpvflow_capture_io_destroy(session);

    struct MPVFlowCaptureMeta differing_dimensions = {
        "native-media3", "vulkan", 512, 288, 1920, 1080, 512, 288, 128, 72,
        20.0, 20.04, 20.02, .5, "{}", 0, 0, 0, 0
    };
    session = mpvflow_capture_io_start(argv[2], "differing_dimensions",
        &differing_dimensions, reason, sizeof(reason));
    assert(session);
    uint8_t *source_tile = calloc((size_t) source_tile_width * source_tile_height, 4);
    uint8_t *output_tile = calloc((size_t) output_tile_width * output_tile_height, 4);
    assert(source_tile && output_tile);
    assert(!mpvflow_capture_io_write(session, 0, -1, "source0",
        &differing_dimensions, source_tile, source_tile_width, source_tile_height,
        reason, sizeof(reason)));
    assert(!mpvflow_capture_io_write(session, 0, 0, "synthesized",
        &differing_dimensions, output_tile, output_tile_width, output_tile_height,
        reason, sizeof(reason)));
    assert(mpvflow_capture_io_stop(session) == 0);
    mpvflow_capture_io_destroy(session);
    free(source_tile);
    free(output_tile);

    assert(!mpvflow_capture_io_write_terminal_manifest(argv[2], "no_frames",
        "native-media3", "vulkan", meta.effective_settings_json, 0,
        "no_synthesized_samples"));
    assert(!mpvflow_capture_io_write_terminal_manifest(argv[2], "no_vulkan_readback",
        "native-media3",
        mpvflow_capture_io_backend_label(mpvflow_capture_io_backend_kind("opencl-qcom")),
        meta.effective_settings_json, 0, "capture_readback_requires_vulkan"));
    assert(!mpvflow_capture_io_write_terminal_manifest(argv[2], "paired_terminal",
        "mpv", "unknown", "{\"visibility_resolver\":false}", 1,
        "hdr_dolby_vision_or_unknown_color_refused"));
    assert(mpvflow_capture_io_write_terminal_manifest(argv[2], "paired_terminal_bad",
        "mpv", "unknown", "{\"soft_blend\":false}", 1, "capture_refused"));
    assert(!mpvflow_capture_io_start(argv[2], "../oops", &meta, reason, sizeof(reason)));
    assert(!mpvflow_capture_io_start("/missing", "bad", &meta, reason, sizeof(reason)));
    meta.effective_settings_json = "{\"x\":}";
    assert(!mpvflow_capture_io_start(argv[2], "badmeta", &meta, reason, sizeof(reason)));

    meta.engine = "mpv";
    meta.effective_settings_json = "{}";
    session = mpvflow_capture_io_start(argv[2], "byte_limit", &meta, reason, sizeof(reason));
    assert(session);
    const int large_width = 1024;
    const int large_height = 1024;
    const size_t large_bytes = (size_t) large_width * large_height * 4;
    uint8_t *large = calloc(1, large_bytes);
    assert(large);
    int failed_at = -1;
    for (int sample = 0; sample < 32; sample++) {
        const int pair = sample / 4;
        const int sub_sample = sample % 4;
        if (mpvflow_capture_io_write(session, pair, sub_sample, "synthesized",
              &meta, large, large_width, large_height, reason, sizeof(reason))) {
            failed_at = sample;
            break;
        }
    }
    assert(failed_at >= 0 && strstr(reason, "byte limit"));
    assert(mpvflow_capture_io_stop(session));
    mpvflow_capture_io_destroy(session);
    free(large);
    return 0;
}'''

with tempfile.TemporaryDirectory() as temp_dir:
    directory = pathlib.Path(temp_dir)
    c_source = directory / "fixture.c"
    executable = directory / "fixture"
    c_source.write_text(C_FIXTURE)
    subprocess.run(
        ["cc", "-std=c11", "-Wall", "-Wextra", "-Werror",
         "-D_POSIX_C_SOURCE=200809L", "-I", str(SOURCE.parent),
         str(c_source), str(SOURCE), "-o", str(executable)],
        check=True,
    )
    subprocess.run([str(executable), str(directory / "pixels.png"), str(directory)], check=True)

    png = (directory / "pixels.png").read_bytes()
    position = 8
    compressed = b""
    header = None
    while position < len(png):
        length = struct.unpack(">I", png[position:position + 4])[0]
        chunk_type = png[position + 4:position + 8]
        payload = png[position + 8:position + 8 + length]
        assert zlib.crc32(chunk_type + payload) & 0xFFFFFFFF == struct.unpack(
            ">I", png[position + 8 + length:position + 12 + length]
        )[0]
        if chunk_type == b"IHDR":
            header = payload
        if chunk_type == b"IDAT":
            compressed += payload
        position += length + 12
    expected = bytes([
        0, 255, 0, 0, 255, 0, 255, 0, 128,
        0, 0, 0, 255, 0, 17, 34, 51, 255,
    ])
    assert struct.unpack(">II", header[:8]) == (2, 2)
    assert zlib.decompress(compressed) == expected

    manifest = json.loads((directory / "valid" / "manifest.json").read_text())
    assert manifest["engine"] == "mpv" and manifest["status"] == "partial"
    assert manifest["stopped"] is True
    assert manifest["pair_count"] == 8 and manifest["sample_count"] == 32
    assert manifest["effective_settings"]["x"] == 'q"\n'
    assert any(item["channel"] == "motion_forward" for item in manifest["records"])
    assert any(item["channel"] == "visibility_support" for item in manifest["records"])
    sample_record = next(item for item in manifest["records"] if item["channel"] == "synthesized")
    assert sample_record["pts"] == {"source0": 10.25, "source1": 11.5, "output": 10.75, "timestep": 0.5}
    assert any(item["pair_index"] == 2 and item["sample_index"] == 3 and
               item["stage"] == "map_dispatch" and 'quote' in item["reason"]
               for item in manifest["missing"])
    assert len(manifest["records"]) == 41

    media3_manifest = json.loads((directory / "media3" / "manifest.json").read_text())
    assert media3_manifest["engine"] == "native-media3"
    assert media3_manifest["stopped"] is True
    paired_terminal = json.loads((directory / "paired_terminal" / "manifest.json").read_text())
    assert paired_terminal["paired_output_diagnostic"] is True
    assert paired_terminal["paired_comparison"]["only_differing_setting"] == "soft_blend"
    paired_manifest = json.loads((directory / "paired" / "manifest.json").read_text())
    assert paired_manifest["paired_output_diagnostic"] is True
    assert paired_manifest["limits"]["pairs"] == 1
    assert paired_manifest["limits"]["samples_per_pair"] == 2
    assert paired_manifest["paired_comparison"]["only_differing_setting"] == "soft_blend"
    assert paired_manifest["paired_comparison"]["alpha"] == 0.5
    assert paired_manifest["decision_capture"]["schema"] == "mpvflow-decision-mask-v1"
    assert paired_manifest["image_policy"]["max_dimension"] == 1920
    assert paired_manifest["image_policy"]["color_images"] == "native-resolution RGBA8; no resampling"
    assert paired_manifest["decision_capture"]["branches"]["10"] == "zero-weight source1"
    assert paired_manifest["decision_capture"]["branches"]["11"] == "lowconf source blend"
    assert paired_manifest["decision_capture"]["branches"]["12"] == "conflict source blend"
    assert paired_manifest["decision_capture"]["palette_rgb"]["11"] == [0, 128, 255]
    assert paired_manifest["decision_capture"]["palette_rgb"]["12"] == [255, 192, 64]
    assert paired_manifest["decision_capture"]["conflict_bits"]["8"] == "neighborhood_conflict"
    assert {r["channel"] for r in paired_manifest["records"] if r["channel"].startswith("decision_")} == {"decision_raw", "decision_mask"}
    assert paired_manifest["paired_comparison"]["same_source_textures"] is True
    assert paired_manifest["paired_comparison"]["same_analyzed_motion_field"] is True
    assert "soft_blend" not in paired_manifest["effective_settings"]
    paired_outputs = [item for item in paired_manifest["records"] if item["channel"] == "synthesized"]
    assert [item["variant"] for item in paired_outputs] == ["soft_blend_off", "soft_blend_on"]
    assert [item["soft_blend"] for item in paired_outputs] == [False, True]
    assert [item["visibility_resolver"] for item in paired_outputs] == [False, False]
    assert [item["preserve_motion_blend_on_conflict"] for item in paired_outputs] == [True, True]
    assert paired_outputs[0]["pts"] == paired_outputs[1]["pts"]
    differing = json.loads((directory / "differing_dimensions" / "manifest.json").read_text())
    assert differing["source_dimensions"] == [512, 288]
    assert differing["output_dimensions"] == [1920, 1080]
    source_record = next(item for item in differing["records"] if item["channel"] == "source0")
    output_record = next(item for item in differing["records"] if item["channel"] == "synthesized")
    assert source_record["analysis_dimensions"] == [512, 288]
    assert source_record["grid_dimensions"] == [128, 72]
    assert source_record["analysis_dimensions"] == [512, 288]
    assert source_record["grid_dimensions"] == [128, 72]
    assert (source_record["width"], source_record["height"]) == (512, 288)
    assert (output_record["width"], output_record["height"]) == (640, 360)
    no_frame_manifest = json.loads((directory / "no_frames" / "manifest.json").read_text())
    assert no_frame_manifest["status"] == "failed" and no_frame_manifest["stopped"] is True
    assert no_frame_manifest["terminal_reason"] == "no_synthesized_samples"
    assert no_frame_manifest["pts"] is None and no_frame_manifest["sample_count"] == 0
    readback_manifest = json.loads((directory / "no_vulkan_readback" / "manifest.json").read_text())
    assert readback_manifest["status"] == "failed" and readback_manifest["stopped"] is True
    assert readback_manifest["backend"] == "opencl"
    assert readback_manifest["terminal_reason"] == "capture_readback_requires_vulkan"
    assert readback_manifest["sample_count"] == 0 and readback_manifest["records"] == []
    assert readback_manifest["missing"] == [{
        "pair_index": 0,
        "sample_index": -1,
        "channel": "synthesized",
        "stage": "capture",
        "reason": "capture_readback_requires_vulkan",
    }]
    limit_manifest = json.loads((directory / "byte_limit" / "manifest.json").read_text())
    assert limit_manifest["status"] == "partial" and limit_manifest["bytes_written"] <= 64 * 1024 * 1024

print("mpvflow capture I/O fixture passed")
