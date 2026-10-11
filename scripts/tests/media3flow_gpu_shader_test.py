#!/usr/bin/env python3
"""Guard the shared Vulkan compute architecture used by MPV and Native Media3 Flow."""
from __future__ import annotations

import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SINK = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(encoding="utf-8")
NATIVE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVulkanNative.kt").read_text(encoding="utf-8")
NATIVE_ENGINE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt").read_text(encoding="utf-8")
JNI = (ROOT / "app/src/main/cpp/media3flow_vulkan_jni.cpp").read_text(encoding="utf-8")
CMAKE = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
GPU = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
POLICY = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/RendererBackendPolicy.kt").read_text(encoding="utf-8")
MPV_PATCH = (ROOT / "app/src/main/cpp/mpvflow/mpvflow-vo-gpu-next.patch").read_text(encoding="utf-8")
RUNTIME_BUILD = (ROOT / "scripts/build-mpvflow-mpv-runtime.sh").read_text(encoding="utf-8")
AAR_PACKAGE = (ROOT / "scripts/package-mpv-runtime-aar.py").read_text(encoding="utf-8")

# Native Media3 may use GLES for decoder SurfaceTexture capture and final display only.
for marker in ("copyExternalTexture(", "drawTexture(", "interpolation=Vulkan"):
    assert marker in SINK, f"Native Media3 must retain its capture/display-only GLES stage: {marker}"
assert "GLES20.glFinish()" not in SINK, "input capture must use the native-fence Vulkan handoff instead of a CPU-wide GLES finish"
for forbidden in (
    "GLES31", "#version 310 es", "glDispatchCompute", "glBindImageTexture", "glMemoryBarrier",
    "FlowCoverageBuffer", "lumaProgram", "flowProgram", "synthProgram",
):
    assert forbidden not in SINK, f"Obsolete GLES interpolation compute remains in Media3 sink: {forbidden}"

# Both routes dispatch through the same libplacebo-backed Vulkan flow implementation.
for marker in (
    "nativePrepareFrame(", "nativeAnalyzePair(", "nativeSynthesize(",
    "nativeCreateFrameImage(", "nativeHoldImageForExternal(",
):
    assert marker in SINK, f"Media3 sink is missing its Vulkan JNI dispatch: {marker}"
for marker in (
    "pl_vulkan_create(", "pl_vulkan_wrap(", "pl_vulkan_release_ex(", "pl_vulkan_hold_ex(",
    "VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME", "vkImportSemaphoreFdKHR", "vkGetSemaphoreFdKHR",
    "eglDupNativeFenceFDANDROID", "eglWaitSyncKHR", "sync_handoff_summary",
    "VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID",
    "VK_QUEUE_FAMILY_FOREIGN_EXT", "mpvflow_gpu_create(", "mpvflow_gpu_prepare_input(",
    "mpvflow_gpu_analyze_pair(", "mpvflow_gpu_synthesize(",
):
    assert marker in JNI, f"Vulkan HardwareBuffer bridge is missing required contract: {marker}"
assert "get_proc_addr" in JNI
assert '"vkGetPhysicalDeviceImageFormatProperties2KHR"' in JNI
assert "vkGetPhysicalDeviceImageFormatProperties2(\n" not in JNI, "Android Vulkan entry point must be loaded, not directly linked"
assert "const bool imageOwned = image && context->images.find(image) != context->images.end()" in JNI
assert "stage=frame_registration" in JNI and "frame0Owned" in JNI and "frame1Owned" in JNI
pair_release = JNI.split("Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeReleasePair(", 1)[1].split(
    "Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeDestroyImage(", 1
)[0]
assert "pl_gpu_finish(" not in pair_release, "retiring a motion pair must not serialize the Vulkan queue with a CPU finish"
assert "context->pendingReleaseSemaphores" in JNI and "context->pendingHoldFences" in JNI, (
    "sync-FD semaphores must remain alive until their GPU completion fences are reaped"
)
for marker in (
    "params.async_compute = false",
    "params.async_transfer = false",
    "waitForOldestInteropFence(",
    "EGL_FOREVER_KHR",
    "cpu_sync_wait_total_ms",
):
    assert marker in JNI, (
        "Native Media3 sync-FD handoffs must preserve safe queue ordering and bounded resource pressure: "
        f"{marker}"
    )
assert ".blit_src = blit_src" in GPU and 'ctx, output_w, output_h, ctx->fmt_rgba8,\n                                         true, true, "full_resolution_pair_output"' in GPU, (
    "the full-resolution synthesized Flow output must advertise blit-source capability before Media3 copies it"
)
assert "nativeCreateContext(maxDimension)" in SINK
assert "config.max_dimension = std::max(160, std::min(640, static_cast<int>(requestedMaxDimension)))" in JNI
assert "source0->texture, source1->texture" in JNI
assert "nativeHoldImageForExternal(flowVulkanContext, slot.nativeImageHandle)" in SINK
assert "eglSwapIntervalZeroApplied = EGL14.eglSwapInterval(eglDisplay, 0)" in SINK
assert "outputWallTimes.addLast(swapReturnedNs)" in SINK
assert "outputWallTimes.addLast(frameTimeNanos" not in SINK
assert "eglSwapReturnFps=" in SINK and "outputMetric=successful_eglSwapBuffers_return_not_scanout" in SINK
assert "fp0->flow_source, fp1->flow_source" in MPV_PATCH, "MPV synthesis must use source-resolution color frames"
assert "motion_analysis=%dx%d" in MPV_PATCH and "synthesized_output=%dx%d" in MPV_PATCH
assert "event=runtime_cadence" in MPV_PATCH and "empty_batch_redraws=%llu" in MPV_PATCH
assert "source_frame.overlays = NULL" in MPV_PATCH and "source_frame.num_overlays = 0" in MPV_PATCH
assert ".crop = image0->crop" not in MPV_PATCH, "full-resolution source copies already bake the input crop"
assert "external fun nativeCreateContext" in NATIVE
assert "external fun nativeGetRequiredHardwareBufferUsage" in NATIVE
assert "external fun nativeAnalyzePair" in NATIVE
assert "external fun nativeGetVulkanTimingStats(context: Long): LongArray?" in NATIVE
assert "mpvflow_gpu_get_timing_stats(context->flow, &stats)" in JNI
assert "computeTimestampValidBits" in JNI and "timestamp_valid_bits=%u" in JNI
assert "vulkanTimestampSupported" in JNI and "compute_queue_timestampValidBits_zero" in JNI
assert "pending_or_dropped" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowDiagnostics.kt").read_text()
assert "Vulkan/libplacebo timestamp queries" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text()
assert "GLES_EXT_disjoint_timer_query presentation draw" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text()
timing_snapshot = JNI.split("nativeGetVulkanTimingStats(", 1)[1].split("nativeDestroyContext(", 1)[0]
assert "mpvflow_gpu_get_timing_stats" in timing_snapshot
assert "pl_gpu_finish" not in timing_snapshot and "glFinish" not in timing_snapshot
assert "nativeGetRequiredHardwareBufferUsage(flowVulkanContext, output = false)" in SINK
assert "nativeGetRequiredHardwareBufferUsage(flowVulkanContext, output = true)" in SINK
assert "baseUsage or driverRequiredUsage" in SINK
assert "vulkan_hardwarebuffer_input_usage_query_failed" in SINK
assert "vulkan_hardwarebuffer_output_usage_query_failed" in SINK
assert "queryAhbImageSupport(context, usage, nullptr, &requiredAhbUsage, role)" in JNI
assert "stage=hardwarebuffer_usage_mismatch" in JNI
assert "event=ahb_usage state=required" in JNI
assert "media3FlowSink?.takeIf { it.isPreflightAvailable() }" in NATIVE_ENGINE

# The JNI library must link only against a real AAR export provider; missing API is fatal.
assert 'PATTERNS "jni/${ANDROID_ABI}/libmpv.so"' in CMAKE
assert 'PATTERNS "jni/${ANDROID_ABI}/libplacebo.so"' in CMAKE
assert "-E tar tf" in CMAKE
assert "MPVFLOW_PLACEBO_API_SO" in CMAKE
assert "MPVFLOW_REQUIRED_PLACEBO_SYMBOL" in CMAKE
assert "does not export ${MPVFLOW_REQUIRED_PLACEBO_SYMBOL}" in CMAKE
assert "mpvflow_placebo_api_from_aar" in CMAKE
assert "-Ddefault_library=shared" in RUNTIME_BUILD
assert "LOCAL_MODULE := libplacebo" in RUNTIME_BUILD
assert "LOCAL_SRC_FILES := $(PREFIX)/lib/libplacebo.so" in RUNTIME_BUILD
assert "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv placebo" in RUNTIME_BUILD
assert '"libplacebo.so"' in AAR_PACKAGE
assert "dynamic_defined_symbols" in AAR_PACKAGE
assert "pl_vulkan_release_ex" in AAR_PACKAGE

# MPV Flow is Vulkan-only, automatically selects gpu-next/Vulkan, and never advertises GLES compute.
assert "forceForMpvFlow = flowRequiresVulkan" in MPV_VIEW
assert 'gpuApi == "vulkan"' in POLICY
assert '"requires_gpu_next_vulkan"' in MPV_VIEW
assert "requires_gpu_next_opengl" not in MPV_VIEW
assert "backend=vulkan-spirv" in MPV_PATCH
assert "backend=opengl-es" not in MPV_PATCH
assert "gpu->glsl.vulkan" in GPU
assert 'PlaybackSession.setOptionString("display-fps-override", displayRefreshRate.hz.toString())' in MPV_VIEW
assert "display_refresh_source=" in MPV_VIEW
print("Media3 and MPV Flow preserve source-resolution synthesis, bounded motion analysis, async ownership handoffs, and measured output cadence")
