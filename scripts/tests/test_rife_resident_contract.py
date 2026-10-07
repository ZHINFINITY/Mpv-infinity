#!/usr/bin/env python3
"""Fail CI if the experimental RIFE resident path loses a required safety contract."""
from __future__ import annotations

import argparse
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"RIFE resident contract failed: {message}")


def section(text: str, start: str, end: str) -> str:
    begin = text.find(start)
    require(begin >= 0, f"missing section start {start!r}")
    finish = text.find(end, begin + len(start))
    require(finish >= 0, f"missing section end {end!r}")
    return text[begin:finish]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-source", required=True, type=Path)
    parser.add_argument("--native-bridge", required=True, type=Path)
    args = parser.parse_args()
    mpv = args.mpv_source.resolve()
    bridge_path = args.native_bridge.resolve()

    options_c = (mpv / "options/options.c").read_text()
    options_h = (mpv / "options/options.h").read_text()
    reader = (mpv / "video/out/hwdec/hwdec_aimagereader.c").read_text()
    vo = (mpv / "video/out/vo_gpu_next.c").read_text()
    bridge = bridge_path.read_text()

    # These must be root libmpv options. App-side setOptionString calls must not
    # depend on gpu-next accepting arbitrary VO suboptions.
    for name, field in (
        ("rife-resident", "rife_resident"),
        ("rife-model-dir", "rife_model_dir"),
        ("rife-target-fps", "rife_target_fps"),
        ("rife-max-dimension", "rife_max_dimension"),
    ):
        require(options_c.count(f'{{"{name}",') == 1, f"{name} is not registered once at the option root")
        require(field in options_h, f"MPOpts is missing {field}")

    # One AImageReader owns one callback; per-mapper registration overwrites the
    # callback context and strands one of gpu-next's two frame mappers.
    require(reader.count("AImageReader_setImageListener(") == 2,
            "reader listener must be installed once and cleared once")
    mapper_init = section(reader, "static int mapper_init(", "static void mapper_uninit(")
    require("AImageReader_setImageListener" not in mapper_init,
            "a mapper must not replace the shared reader listener")
    callback = section(reader, "static void image_callback(void *context, AImageReader *reader)\n{",
                       "static bool wait_for_acquire_fence")
    require("struct priv_owner *p = context" in callback and "image_cond" in callback,
            "reader callback must signal owner-level shared state")
    mapper_map = section(reader, "static int mapper_map(",
                         "void *ra_hwdec_aimagereader_get_hardware_buffer")
    require("mp_mutex_lock(&o->acquire_lock)" in mapper_map,
            "release/wait/acquire must be serialized across both mappers")
    require("av_mediacodec_release_buffer(buffer, 1)" in mapper_map,
            "mapper must release the exact decoder frame to the reader surface")
    require("AImageReader_acquireLatestImageAsync" in mapper_map,
            "mapper must acquire through the API-26 async reader")
    fence_wait = mapper_map.find("wait_for_acquire_fence(acquire_fence_fd)")
    ahb_get = mapper_map.find("AImage_getHardwareBuffer(p->image, &hwbuf)")
    require(0 <= fence_wait < ahb_get,
            "the AImage acquire fence must signal before accessing/importing its AHB")
    no_buffer = mapper_map[mapper_map.find("AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE"):]
    no_buffer = section(no_buffer, "AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE", "if (ret != AMEDIA_OK)")
    require("mp_mutex_unlock(&o->acquire_lock)" in no_buffer and "return 0" in no_buffer,
            "a transient no-buffer result must unlock and preserve playback")
    getter = section(reader, "void *ra_hwdec_aimagereader_get_hardware_buffer(",
                     "const struct ra_hwdec_driver")
    require("!p->image || !p->hardware_buffer" in getter,
            "AHB getter must require a live AImage lease")
    require("*timestamp_ns = p->timestamp_ns" in getter and "p->hardware_buffer" in getter,
            "AHB getter must expose the lease timestamp for exact frame pairing")

    # ncnn retains the source AHB before the AImage lease can be released, and
    # the RIFE output is synchronously written to an EGL-importable AHB image.
    importer = section(bridge, "extern \"C\" int rife_vfi_import_ahb_rgb8(",
                       "extern \"C\" int rife_vfi_interpolate_gpu_frames(")
    require("AHardwareBuffer_acquire(hardware_buffer)" in importer,
            "NCNN input import must retain its AHB before the AImage lease ends")
    require("rife_vfi_write_output_rgba" in bridge and "vkDeviceWaitIdle" in bridge,
            "Vulkan output handoff must complete before GLES presentation")

    # Reject mismatched AImage timestamps; render and submit the original mpv
    # mix if RIFE output creation or presentation fails.
    require("pts_delta > 0.005" in vo and "!image_timestamp_ns" in vo,
            "resident inference must reject unpaired/untimestamped AImages")
    require("rife_vfi_import_ahb_rgb8" in vo and "rife_vfi_write_output_rgba" in vo,
            "VO must perform input import and synthesized output handoff")
    draw = section(vo, "static bool draw_frame(", "static void flip_page(")
    require("source_mix = mix;" in draw and "if (!render_ok && rife_interpolated)" in draw,
            "the original PTS-aware mix must be retained for fallback")
    fallback = draw[draw.find("if (!render_ok && rife_interpolated)"):]
    require("mix = source_mix;" in fallback and "render_ok = pl_render_image_mix" in fallback,
            "generated-frame render failure must retry the original mpv mix")
    flip = section(vo, "static void flip_page(", "static void get_vsync(")
    require("if (submitted && p->rife_pending_frame)" in flip and
            "event=gpu_resident_frame" in flip,
            "gpu_resident_frame must be emitted only after a successful swapchain submit")

    print("RIFE resident contracts passed: root options, shared async reader lease, AHB import/output, fallback, and submitted-frame diagnostic")


if __name__ == "__main__":
    main()
