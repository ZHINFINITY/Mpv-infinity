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
    "mpv", "vulkan", 1920, 1080, 1920, 1080,
    10.25, 11.5, 10.75, .5, "{\"x\":\"q\\\"\\n\"}"
};

int main(int argc, char **argv) {
    (void) argc;
    uint8_t px[16] = {
        255,0,0,255, 0,255,0,128,
        0,0,255,0, 17,34,51,255
    };
    char reason[256];
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
    assert(!mpvflow_capture_io_write_terminal_manifest(argv[2], "no_frames",
        "native-media3", "vulkan", meta.effective_settings_json,
        "no_synthesized_samples"));
    assert(!mpvflow_capture_io_write_terminal_manifest(argv[2], "no_vulkan_readback",
        "native-media3",
        mpvflow_capture_io_backend_label(mpvflow_capture_io_backend_kind("opencl-qcom")),
        meta.effective_settings_json, "capture_readback_requires_vulkan"));
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
