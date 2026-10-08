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
    app_view = (
        Path(__file__).resolve().parents[2]
        / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
    )
    native_build = (Path(__file__).resolve().parents[1] / "build-rife-native.sh").read_text()

    options_c = (mpv / "options/options.c").read_text()
    options_h = (mpv / "options/options.h").read_text()
    reader = (mpv / "video/out/hwdec/hwdec_aimagereader.c").read_text()
    vo = (mpv / "video/out/vo_gpu_next.c").read_text()
    bridge = bridge_path.read_text()
    view = app_view.read_text()
    process_dimensions = (
        Path(__file__).resolve().parents[2]
        / "app/src/main/cpp/rife/rife_vfi_process_dimensions.h"
    ).read_text()
    prepare_script = (Path(__file__).resolve().parents[1] / "prepare-rife-mpv.py").read_text()
    require("DisplayManager" in view and "Display.DEFAULT_DISPLAY" in view,
            "Android display refresh fallback must use DisplayManager when the view is unattached")
    require('setOptionString("display-fps-override"' in view and
            "displayRefreshRateAvailable" in view,
            "resident scheduling must supply and require a usable display refresh rate")
    require('isOwnedByMpvConf("display-fps-override")' in view,
            "the resident route must not override a user-owned display refresh setting")
    require('cp "$ROOT/app/src/main/cpp/rife/rife_vfi_output_layout.h" "$RIFE_DIR/src/rife_vfi_output_layout.h"' in native_build,
            "the native build must stage the private output-layout header beside the bridge")
    require('shutil.copy2(VENDOR / "rife_vfi_process_dimensions.h",' in prepare_script and
            'video_filter_dir / "rife_vfi_process_dimensions.h"' in prepare_script,
            "the shared processing-dimensions header must be staged into the MPV include path")

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
                       "static int mapper_init(")
    require("struct priv_owner *p = context" in callback and "image_cond" in callback,
            "reader callback must signal owner-level shared state")
    mapper_map = section(reader, "static int mapper_map(",
                         "void *ra_hwdec_aimagereader_get_hardware_buffer")
    require("mp_mutex_lock(&o->acquire_lock)" in mapper_map,
            "release/wait/acquire must be serialized across both mappers")
    require("av_mediacodec_release_buffer(buffer, 1)" in mapper_map,
            "mapper must release the exact decoder frame to the reader surface")
    require("AImageReader_acquireLatestImage(o->reader, &p->image)" in mapper_map,
            "mapper must retain the upstream synchronous image acquire path")
    require("AImageReader_acquireLatestImageAsync" not in reader and
            "wait_for_acquire_fence" not in reader and "poll(" not in reader,
            "renderer-thread AImage acquire-fence polling must remain disabled")
    artifact_verifier = Path(__file__).with_name("test_rife_android_mpv_artifact.py").read_text()
    require('b"AImageReader_acquireLatestImage\\0" not in library' in artifact_verifier and
            'if b"AImageReader_acquireLatestImageAsync" in library:' in artifact_verifier,
            "APK artifact validation must enforce the synchronous, non-polling reader path")
    callback_wait = mapper_map.find("mp_cond_timedwait(&o->image_cond")
    acquire = mapper_map.find("AImageReader_acquireLatestImage(o->reader, &p->image)")
    ahb_get = mapper_map.find("AImage_getHardwareBuffer(p->image, &hwbuf)")
    require(0 <= callback_wait < acquire < ahb_get,
            "bounded callback wait and synchronous acquire must precede AHB access")
    no_buffer = mapper_map[mapper_map.find("AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE"):]
    no_buffer = section(no_buffer, "AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE", "if (ret != AMEDIA_OK)")
    require("mp_mutex_unlock(&o->acquire_lock)" in no_buffer and "return 0" in no_buffer,
            "a transient no-buffer result must unlock and preserve playback")
    require("p->hardware_buffer = NULL" in no_buffer,
            "a no-buffer result must clear the borrowed AHB lease")
    require("rife_disabled_for_session" not in no_buffer,
            "a transient no-buffer result must not permanently disable resident RIFE")
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
    output_transition = section(bridge, "bool record_output_transition(",
                                "void destroy_output_resources(")
    require("vkdev->acquire_queue(family)" in output_transition and
            output_transition.count("vkdev->reclaim_queue(family, queue)") == 2 and
            "vkQueueWaitIdle(queue)" in output_transition,
            "ownership transitions must borrow and return their NCNN queue on every submit path")
    output_init = section(bridge, "bool initialize_output_slot(", "\n#endif")
    require("transition_queue" not in bridge and "acquire_queue(" not in output_init,
            "output slots must not reserve compute queues needed by RIFE inference")
    require("vkdev->info.compute_queue_count()" in output_init and
            "stage=compute_queue_unavailable" in output_init,
            "output setup must fail closed instead of waiting forever without a compute queue")
    require("rife_vfi_write_output_rgba" in bridge and
            "cmd.submit_and_wait()" in bridge and
            "if (submit_result != 0)" in bridge and
            "vkDeviceWaitIdle" not in bridge,
            "output handoff must wait for its compute submission without a device-wide stall")
    output_writer = section(
        bridge,
        'extern "C" int rife_vfi_write_output_rgba(',
        'extern "C" void rife_vfi_output_destroy(',
    )
    require("rife_vfi_output_layout_classify" in output_writer and
            "output_rgba_fp32_pipeline" in output_writer and
            "stage=output_contract_failed" in output_writer and
            "input_dims=%d input_w=%d input_h=%d input_d=%d input_c=%d input_elempack=%d input_elemsize=%zu input_cstep=%zu" in output_writer,
            "output writer must bridge packed RGB8 and planar FP32 with tensor-field diagnostics")
    output_init = section(bridge, "bool initialize_output_slot(", "\n#endif")
    require("output->image_memory.refcount = 1;" in output_init and
            "output->image_memory.refcount = 0;" not in output_init and
            "ncnn::VkImageMat(width, height, &output->image_memory" in output_init,
            "external output AHB metadata must remain caller-owned during ncnn command cleanup")
    for stage in (
        "stage=output_precondition_failed",
        "stage=vulkan_output_extensions_unavailable",
        "stage=external_image_format_query_failed",
        "stage=external_image_not_importable",
        "stage=android_usage_flags_missing",
        "stage=output_extent_exceeded",
        "stage=ahb_allocate_failed",
        "stage=ahb_descriptor_mismatch",
        "stage=ahb_properties_import_failed",
        "stage=vk_create_image_failed",
        "stage=memory_type_unavailable",
        "stage=vk_allocate_imported_memory_failed",
        "stage=vk_bind_imported_memory_failed",
        "stage=vk_create_image_view_failed",
        "stage=wrapper_allocator_unavailable",
        "stage=output_image_wrapper_empty",
        "stage=compute_queue_unavailable",
        "stage=vk_create_transition_pool_failed",
        "stage=vk_allocate_transition_command_failed",
    ):
        require(stage in output_init, f"native output-ring diagnostics are missing {stage}")
    properties_check = section(
        output_init,
        "result = vkdev->vkGetAndroidHardwareBufferPropertiesANDROID(",
        "VkExternalMemoryImageCreateInfo external_image{};",
    )
    require("format_properties.format != VK_FORMAT_R8G8B8A8_UNORM" in properties_check,
            "output AHB import must reject a mismatched concrete Vulkan format")
    require("format_properties.externalFormat != 0" not in properties_check,
            "a nonzero externalFormat token must not reject a concrete VkFormat")
    require("!buffer_properties.allocationSize" in properties_check and
            "!buffer_properties.memoryTypeBits" in properties_check and
            "format_properties.externalFormat" in properties_check,
            "AHB validation must retain allocation/memory checks and log externalFormat")
    create_image = section(output_init, "result = vkCreateImage(",
                           "VkImportAndroidHardwareBufferInfoANDROID import_info{};")
    require("image_create.format = format_properties.format" in output_init and
            "if (result != VK_SUCCESS)" in create_image and
            "stage=vk_create_image_failed" in create_image,
            "concrete-format image creation must remain checked and fail closed")
    require("import_info.buffer = output->hardware_buffer" in output_init and
            "dedicated_info.image = output->image_memory.image" in output_init and
            "memory_info.allocationSize = buffer_properties.allocationSize" in output_init and
            "buffer_properties.memoryTypeBits" in output_init,
            "Vulkan memory import must use the allocated AHB, dedicated image, and queried properties")
    allocate_imported = section(output_init, "result = vkAllocateMemory(",
                                "VkBindImageMemoryInfo bind_info{};")
    bind_imported = section(output_init, "result = vkdev->vkBindImageMemory2KHR(",
                            "VkImageViewCreateInfo view_info{};")
    require("if (result != VK_SUCCESS)" in allocate_imported and
            "stage=vk_allocate_imported_memory_failed" in allocate_imported and
            "if (result != VK_SUCCESS)" in bind_imported and
            "stage=vk_bind_imported_memory_failed" in bind_imported,
            "imported-memory allocation and image binding must remain fail-closed")
    output_create = section(bridge, "extern \"C\" RifeVfiOutput *rife_vfi_output_create(",
                            "extern \"C\" void *rife_vfi_output_get_ahb(")
    require("if (!initialized)\n            destroy_output_resources(output.get());" in output_create,
            "partially initialized AHB/Vulkan resources must be released on failure")
    ring_init = section(vo, "static bool rife_init_output_ring(",
                        "static bool rife_prepare_frame_input(")
    require("reason=output_ring_native_create_failed" in ring_init and
            "slot=%d width=%d height=%d detail=%s" in ring_init,
            "native output-ring failure details must reach the Android log")
    require("reason=output_ring_egl_import_failed" in ring_init and
            "egl_error=0x%x" in ring_init,
            "EGL import failures must expose the slot and EGL error")

    # Reject mismatched AImage timestamps; render and submit the original mpv
    # mix if RIFE output creation or presentation fails.
    require("pts_delta > 0.005" in vo and "!image_timestamp_ns" in vo,
            "resident inference must reject unpaired/untimestamped AImages")
    require("rife_vfi_import_ahb_rgb8" in vo and "rife_vfi_write_output_rgba" in vo,
            "VO must perform input import and synthesized output handoff")
    require("char output_error[512]" in vo and "detail=%s" in vo,
            "output writer diagnostics must be propagated into the Android playback log")
    import_branch = section(vo, "} else if (rife_vfi_import_ahb_rgb8(",
                            "RIFE_DIAGNOSTIC event=ahb_input_imported")
    require("} else {" in import_branch,
            "ahb_input_imported must be reachable only after a successful AHB import")
    draw = section(vo, "static bool draw_frame(", "static void flip_page(")
    require("source_mix = mix;" in draw and "if (!render_ok && rife_interpolated)" in draw,
            "the original PTS-aware mix must be retained for fallback")
    fallback = draw[draw.find("if (!render_ok && rife_interpolated)"):]
    require("mix = source_mix;" in fallback and "render_ok = pl_render_image_mix" in fallback,
            "generated-frame render failure must retry the original mpv mix")
    require(draw.find("source_mix = mix;") <
            draw.find("if (rife_interpolated) {\n        mix = rife_mix;") <
            draw.find("pl_render_image_mix(p->rr, &mix"),
            "only a fully prepared RIFE frame may replace the original render mix")
    flip = section(vo, "static void flip_page(", "static void get_vsync(")
    require("if (submitted && p->rife_pending_frame)" in flip and
            "event=gpu_resident_frame" in flip,
            "gpu_resident_frame must be emitted only after a successful swapchain submit")
    present_failure = section(flip, "if (!submitted) {", "p->frame_pending = false;")
    require("#if defined(__ANDROID__)" in present_failure and
            "p->rife_pending_frame" in present_failure,
            "resident-submit failure diagnostics must not break non-Android MPV builds")
    require("rife_cadence_origin_from_presentation" in vo,
            "resident target cadence must align to the presentation PTS phase")
    require("target_fps=%d cadence_origin_pts=%.6f" in vo,
            "resident wait events must include target FPS and cadence origin")
    require("frame->num_frames > 1 || resident_rife_active" in draw and
            "frame->display_synced" in draw,
            "resident RIFE must keep display-PTS scheduling active for one-frame VO batches")
    prepare = section(vo, "static bool rife_prepare_frame_input(",
                      "static bool rife_build_display_mix(")
    require("mutable_frame->acquire(p->gpu, mutable_frame)" in prepare and
            "mutable_frame->release(p->gpu, mutable_frame)" in prepare,
            "resident pair inputs must be acquired/imported and released before readiness checks")
    hwdec_release = section(vo, "static void hwdec_release(",
                            "static bool format_supported(")
    require("slot_release(&p->hwdec);" in hwdec_release and
            "talloc_free(" not in hwdec_release,
            "preflight release must end decoder access without freeing queue-owned frames")
    resident_mix = section(vo, "static bool rife_build_display_mix(",
                           "static void update_options(")
    require('#include "video/filter/rife_vfi_process_dimensions.h"' in vo and
            vo.count("rife_vfi_process_dimensions(") == 2 and
            "rife_vfi_process_dimensions(mpi->params.w, mpi->params.h," in vo and
            "rife_vfi_process_dimensions(mpi0->params.w, mpi0->params.h," in vo,
            "AHB input import and output-ring sizing must use the same dimensions policy")
    require("target_fps >= 60 ? 480 : target_fps >= 48 ? 720 : 1080" in process_dimensions,
            "Auto processing limits must live in the shared dimensions helper")
    ring_failure = section(resident_mix, "if (!rife_init_output_ring(p, width, height))",
                           "int index = p->rife_output_cursor")
    require("return false;" in ring_failure,
            "output-ring failure must return before constructing/replacing the render mix")
    require("max_dimension == 0" not in resident_mix,
            "output-ring sizing must not independently resolve Auto dimensions")
    require(resident_mix.find("rife_prepare_frame_input(p, source)") <
            resident_mix.find("!p->rife_diag_before_input_ready"),
            "both selected source frames must be pre-acquired before pair availability is tested")
    require("mix_frames=%d queue_depth=%d" in vo and "vo_frames=%d" in vo and
            "display_synced=%d" in vo and "pair_after_pts=%.6f" in vo,
            "wait diagnostics must distinguish render-mix size from queue depth and expose timing/pair state")
    require("source_frame_boundary_no_intermediate" in vo,
            "source-frame boundary ticks must not be mislabeled as invalid interpolation fractions")
    for marker in (
        "event=resident_wait", "event=resident_error",
        "event=resident_output_ready", "event=resident_rendered",
        "event=resident_present_failed",
    ):
        require(marker in vo, f"resident runtime diagnostics are missing {marker}")

    print(
        "RIFE resident contracts passed: root options, Android display timing, shared reader lease, "
        "AHB import/output, fallback, submitted-frame diagnostic, and runtime stage diagnostics"
    )


if __name__ == "__main__":
    main()
