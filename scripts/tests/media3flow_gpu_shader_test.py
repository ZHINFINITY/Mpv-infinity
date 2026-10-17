#!/usr/bin/env python3
"""Guard the shared Vulkan compute architecture used by MPV and Native Media3 Flow."""
from __future__ import annotations

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
SINK = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(encoding="utf-8")
CADENCE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowCadence.kt").read_text(encoding="utf-8")
MEDIA3_ANALYSIS_POLICY = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowAnalysisPolicy.kt").read_text(encoding="utf-8")
FLOW_DIAGNOSTICS = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowDiagnostics.kt").read_text(encoding="utf-8")
NATIVE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVulkanNative.kt").read_text(encoding="utf-8")
NATIVE_ENGINE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt").read_text(encoding="utf-8")
DEBUG_SETTINGS = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowDebugSettings.kt").read_text(encoding="utf-8")
DECODER_PREFERENCES = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/preferences/DecoderPreferencesScreen.kt").read_text(encoding="utf-8")
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

# GLES uniform locations are stable for a linked program. Query them at setup,
# not from the per-frame capture or presentation paths.
draw_texture = SINK.split("  private fun drawTexture(", 1)[1].split(
    "\n  private fun defaultPresentationFrame(", 1
)[0]
copy_external_texture = SINK.split("  private fun copyExternalTexture(", 1)[1].split(
    "\n  private fun makeSharedImageExternal(", 1
)[0]
assert "glGetUniformLocation" not in draw_texture, "presentation must not query GLES uniforms per output frame"
assert "glGetUniformLocation" not in copy_external_texture, "capture must not query GLES uniforms per input frame"
assert "MAX_PRESENTATION_BUFFERS = 2" in SINK, "async presentation must stay within a two-buffer memory bound"
assert "presentationQueue.acquireForWriteWithSnapshot()" in draw_texture and "presentationQueue.publish(lease)" in draw_texture, (
    "producer output must enter the bounded latest-wins presentation mailbox"
)
assert "eglSwapBuffers" not in draw_texture, "the producer GL handler must not block on the window swap"
presentation_consumer = SINK.split("  private fun presentNextQueuedFrame()", 1)[1].split(
    "\n  private fun resumePresentationDrain()", 1
)[0]
assert "GLES30.glWaitSync" in presentation_consumer and "eglSwapBuffers" in presentation_consumer, (
    "the presenter must wait for staged GPU work and own eglSwapBuffers"
)
assert "GLES30.glClientWaitSync" in SINK and "presentationQueue.finishPresentation(index)" in SINK, (
    "staging slots must not be reused until the presenter-side GPU read fence completes"
)
assert "consumePresentationGlError()" in presentation_consumer and "eglPresentationTimeANDROID" in presentation_consumer, (
    "presenter-side GLES draw and EGL presentation-time failures must be checked before swapping"
)
assert "PRESENTATION_FENCE_TIMEOUT_NS" in SINK and "presentationQueue.quarantinePresentation(index)" in SINK, (
    "uncertain GPU ownership must time out into a permanently unavailable slot, not unsafe reuse"
)
assert "presentationReleaseStarted" in SINK, "presenter release must be idempotent after fence completion"
assert "handlePresenterException(error)" in SINK, "unexpected presenter exceptions must not strand an owned slot"
queue_source = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowPresentationQueue.kt").read_text(encoding="utf-8")
assert "QUARANTINED" in queue_source and "fun quarantinePresentation(slot: Int)" in queue_source
for marker in (
    'copyExternalLocation = if (copyProgram != 0) GLES20.glGetUniformLocation(copyProgram, "uExternal")',
    'copyTextureMatrixLocation = if (copyProgram != 0) GLES20.glGetUniformLocation(copyProgram, "uTextureMatrix")',
    'blitImageLocation = if (blitProgram != 0) GLES20.glGetUniformLocation(blitProgram, "uImage")',
    'blitTextureScaleLocation = if (blitProgram != 0) GLES20.glGetUniformLocation(blitProgram, "uTextureScale")',
    'blitTextureOffsetLocation = if (blitProgram != 0) GLES20.glGetUniformLocation(blitProgram, "uTextureOffset")',
):
    assert marker in SINK, f"GLES uniform must be cached after shader linking: {marker}"

# Both routes dispatch through the same libplacebo-backed Vulkan flow implementation.
for marker in (
    "nativePrepareFrame(", "nativeAnalyzePair(", "nativeSynthesize(",
    "nativeCreateFrameImage(", "nativeHoldImageForExternal(",
):
    assert marker in SINK, f"Media3 sink is missing its Vulkan JNI dispatch: {marker}"
assert "private val visibilityResolverEnabled: Boolean" in SINK
assert "private val softBlendEnabled: Boolean" in SINK
assert "visibility_resolver=$visibilityResolverEnabled soft_blend=$softBlendEnabled" in SINK
assert re.search(r"visibilityResolverEnabled,\s*softBlendEnabled,\s*outputImageHandle", SINK)
assert "visibilityResolverEnabled: Boolean" in NATIVE and "softBlendEnabled: Boolean" in NATIVE
assert "jboolean visibilityResolverEnabled, jboolean softBlendEnabled" in JNI
assert re.search(
    r"visibilityResolverEnabled == JNI_TRUE,\s*softBlendEnabled == JNI_TRUE,\s*softBlendEnabled == JNI_TRUE, &synthesized",
    JNI,
), "Native Media3 must pass resolver, soft blend, and the existing conflict bias into shared synthesis"
assert re.search(
    r"visibilityResolverEnabled == JNI_TRUE,\s*softBlendEnabled == JNI_TRUE,\s*softBlendEnabled == JNI_TRUE\);",
    JNI,
), "capture metadata must record the effective Native Media3 synthesis settings"
assert "mpvflow_gpu_synthesize_with_policy(" in MPV_PATCH, "MPV must use the shared continuous-motion policy"
mpv_synthesis_call = MPV_PATCH.split("mpvflow_gpu_synthesize_with_policy(", 1)[1].split(");", 1)[0]
assert mpv_synthesis_call.count("p->next_opts->mpvflow_soft_blend,") == 2, "MPV soft blend must explicitly enable both the source blend and conflict bias"
assert "mpvflow_gpu_synthesize_with_visibility(" not in MPV_PATCH, "MPV must not bypass its opt-in conflict policy"
paired_helper = GPU.split("enum MPVFlowGPUResult mpvflow_gpu_capture_paired_sample(", 1)[1].split("\n}", 1)[0]
assert "const bool blend_variants[MPVFLOW_CAPTURE_PAIRED_SAMPLES_PER_PAIR] = {\n        false, true," in paired_helper
assert "ctx, pair, source0, source1, timestep,\n            false, soft_blend, preserve_motion_blend_on_conflict, &variant_output" in paired_helper
assert "ctx, pair, source0, source1, variant_output, timestep,\n            source_pts0, source_pts1, output_pts,\n            false, soft_blend, preserve_motion_blend_on_conflict" in paired_helper
assert "production_visibility_resolver, production_soft_blend,\n        production_preserve_motion_blend_on_conflict, production_output" in paired_helper
assert "mpvflow_gpu_capture_paired_sample(" in MPV_PATCH and "p->flow_pair, fp0->flow_source, fp1->flow_source, t," in MPV_PATCH
assert "mpvflow_gpu_capture_paired_sample(" in JNI and "source0->texture, source1->texture, timestep," in JNI
assert "Media3FlowDebugSettingsPolicy.effective(" in NATIVE_ENGINE
assert "visibilityResolverEnabled || media3FlowDebugSettings.softBlendEnabled" in NATIVE_ENGINE
assert "MpvFlowComputeApi.VULKAN" in NATIVE_ENGINE
assert "media3FlowVisibilityResolverDebug =" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").read_text(encoding="utf-8")
assert "media3FlowSoftBlendDebug =" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").read_text(encoding="utf-8")
assert "nativeMedia3FlowAvailable" in DECODER_PREFERENCES and "controlsVisible(" in DECODER_PREFERENCES
assert "softBlendEnabled = debugBuild && requestedSoftBlend" in DEBUG_SETTINGS
assert "requestedVisibilityResolver && !softBlendEnabled" in DEBUG_SETTINGS
for marker in (
    "pl_vulkan_create(", "pl_vulkan_wrap(", "pl_vulkan_release_ex(", "pl_vulkan_hold_ex(",
    "VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME", "vkImportSemaphoreFdKHR", "vkGetSemaphoreFdKHR",
    "eglDupNativeFenceFDANDROID", "eglWaitSyncKHR", "sync_handoff_summary",
    "VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID",
    "VK_QUEUE_FAMILY_FOREIGN_EXT", "mpvflow_gpu_create(", "mpvflow_gpu_prepare_input(",
    "mpvflow_gpu_analyze_pair(", "mpvflow_gpu_synthesize_with_policy(",
):
    assert marker in JNI, f"Vulkan HardwareBuffer bridge is missing required contract: {marker}"
shared_synthesis_bindings = GPU.split("struct pl_desc_binding sb[8] = {", 1)[1].split("};", 1)[0]
for source in ("source0", "source1"):
    assert (
        f".object = {source}, .address_mode = PL_TEX_ADDRESS_CLAMP,\n          .sample_mode = PL_TEX_SAMPLE_LINEAR"
        in shared_synthesis_bindings
    ), f"Media3 shared Vulkan synthesis must use the same clamp-to-edge linear sampler for {source}"
assert "PL_FMT_CAP_LINEAR" in GPU, "Media3 source images must be capability-checked before linear filtering"
assert "selectParentSeed(" in GPU and "sadAt(p,candidate,sz,bestCost,blockSize)" in GPU, (
    "Media3 shared Vulkan motion estimation must validate coarse-vector candidates against the child block SAD"
)
assert "parentResidual>0.12||centerResidual>0.12" in GPU, (
    "Media3 shared Vulkan motion estimation must expand local search when a confident coarse seed mismatches its child block"
)
assert "flowGridAxis(" in GPU and "flowGridCoord(p,direction)" in GPU, (
    "Media3 and MPV must share actual terminal block-grid coordinates in Vulkan synthesis"
)
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
assert ".blit_src = blit_src" in GPU and ".blit_dst = blit_dst" in GPU and (
    'ctx, output_w, output_h, output_fmt,\n                                         true, true,\n                                         ctx->compute_api == MPVFLOW_GPU_COMPUTE_OPENCL,' in GPU
), (
    "full-resolution output must enable Vulkan transfer usage for OpenCL interop while retaining blit capabilities"
)
assert "nativeCreateContext(maxDimension, requestedComputeApi.nativeValue)" in SINK
assert "config.max_dimension = std::max(160, std::min(640, static_cast<int>(requestedMaxDimension)))" in JNI
assert "EXTRA_ANALYSIS_PIXELS = 96" in MEDIA3_ANALYSIS_POLICY and "coerceAtMost(640)" in MEDIA3_ANALYSIS_POLICY, (
    "Media3's denser motion-analysis allowance must stay bounded and independent of the shared MPV profile"
)
analysis_admission = SINK.split("  private fun analyzeDeferredMotionPair()", 1)[1].split(
    "\n  private fun renderAtPosition(", 1
)[0]
assert "lastEglSwapWaitMs" not in analysis_admission, (
    "a slow display swap must not suppress bounded Media3 motion-pair analysis"
)
render_decision = SINK.split("  private fun renderAtPosition(", 1)[1].split(
    "\n  private fun findSourcePair(", 1
)[0]
synthesis_admission = render_decision.split("    val synthesize =", 1)[1].split(
    "\n    val nowNs", 1
)[0]
assert "lastEglSwapWaitMs" not in synthesis_admission and "swapBudgetAvailable" not in render_decision and "canSynthesizeAfterEglSwap" not in render_decision, (
    "a prior EGL wait must not veto a ready Media3 interpolation pair"
)
assert "motionAdmissionProxyMs <" in render_decision, (
    "motion admission must retain its source-pair deadline proxy"
)
assert "drawSourceFrame(sourceFrame, renderTimeNs, targetPtsUs, presentationFrame(false" in render_decision, (
    "source-frame fallback must remain available when synthesis is unavailable"
)
for marker in (
    "checkedAdd(bufferPresentationTimeUs, inputTimestampAdjustmentUs)",
    "checkedSubtract(frame1.ptsUs, frame0.ptsUs)",
    "checkedAdd(it, inputTimestampAdjustmentUs)",
):
    assert marker in SINK, f"Media3 Flow must reject wrapped PTS arithmetic at the sink boundary: {marker}"
for marker in (
    "fun checkedAdd(a: Long, b: Long): Long?",
    "fun checkedSubtract(a: Long, b: Long): Long?",
    "checkedSubtract(positionUs, originUs)",
    "checkedSubtract(source1Us, source0Us)",
):
    assert marker in CADENCE, f"Media3 Flow cadence must use checked signed timestamp math: {marker}"
assert "fun motionAdmissionProxyMs(" in CADENCE
assert "motionDeadlineAdmission=cpu_submit_proxy_not_gpu_completion" in SINK
non_monotonic_capture = SINK.split(
    "if (previousPts != null && ptsUs <= previousPts)", 1
)[1].split("evictOneFrameIfNeeded()", 1)[0]
assert "droppedFrames.incrementAndGet()" in non_monotonic_capture
assert "it.onFrameDropped()" in non_monotonic_capture
synthesis_jni = JNI.split(
    "Java_app_infinity_mpvz_ui_player_Media3FlowVulkanNative_nativeSynthesize(", 1
)[1]
assert "!std::isfinite(timestep)" in synthesis_jni, "Media3 JNI must reject NaN/infinite synthesis fractions"
assert synthesis_jni.index("!std::isfinite(timestep)") < synthesis_jni.index("releaseImageToVulkan(") < synthesis_jni.index(
    "mpvflow_gpu_synthesize_with_policy(",
), "invalid synthesis fractions must be rejected before ownership handoff or GPU dispatch"
resource_setup = SINK.split("  private fun ensureProcessingResources(", 1)[1].split(
    "\n  private fun clearSourceFrames(", 1
)[0]
resource_reuse_guard = resource_setup.split("if (decoderWidth == decoderInputWidth", 1)[1].split(") return", 1)[0]
assert "decoderHeight == decoderInputHeight" in resource_reuse_guard, (
    "Media3 Flow may reuse the decoder surface only when its input height is unchanged"
)
assert "sourceWidth == frameWidth && sourceHeight == frameHeight" in resource_reuse_guard, (
    "Media3 Flow may reuse storage only when the capped interpolation dimensions are unchanged"
)
assert re.search(r"framePool\.isNotEmpty\(\)\s*&&\s*flowAvailable", resource_reuse_guard), (
    "same-resolution stream initialization must retry shared resources after Flow has fallen back"
)
assert "source0->texture, source1->texture" in JNI
assert "nativeHoldImageForExternal(flowVulkanContext, slot.nativeImageHandle)" in SINK
assert "eglSwapIntervalZeroApplied = EGL14.eglSwapInterval(eglDisplay, 0)" in SINK
assert "outputWallTimes.addLast(swapReturnedNs)" in SINK
assert "outputWallTimes.addLast(frameTimeNanos" not in SINK
assert "eglSwapReturnFps=" in SINK and "outputMetric=successful_eglSwapBuffers_return_not_scanout" in SINK
assert "fp0->flow_source, fp1->flow_source" in MPV_PATCH, "MPV synthesis must use the high-precision pre-FRC source copies"
assert "motion_analysis=%dx%d" in MPV_PATCH and "synthesis_processing=%dx%d" in MPV_PATCH
assert "presentation_target=%dx%d video_params=%dx%d" in MPV_PATCH
assert "event=runtime_cadence" in MPV_PATCH and "empty_batch_redraws=%llu" in MPV_PATCH
assert "source_frame.overlays = NULL" in MPV_PATCH and "source_frame.num_overlays = 0" in MPV_PATCH
assert ".crop = image0->crop" not in MPV_PATCH, "pre-FRC synthesis copies already bake the input crop"
assert "external fun nativeCreateContext" in NATIVE
assert "external fun nativeGetRequiredHardwareBufferUsage" in NATIVE
assert "external fun nativeAnalyzePair" in NATIVE
assert "external fun nativeGetVulkanTimingStats(context: Long): LongArray?" in NATIVE
assert "mpvflow_gpu_get_timing_stats(context->flow, &stats)" in JNI
assert "vkGetPhysicalDeviceProperties(vk->phys_device, &device_properties)" in GPU
assert "device_properties.limits.timestampPeriod" in GPU
assert "device_properties.limits.timestampComputeAndGraphics" in GPU
assert "vkGetPhysicalDeviceQueueFamilyProperties(vk->phys_device" in GPU
assert "compute_timestamps_supported" in GPU and "timestamp_compute_and_graphics" in GPU
assert "VK_QUERY_RESULT_WAIT_BIT" not in GPU and "pl_timer_query(ctx->gpu" in GPU
assert "computeTimestampValidBits" in JNI and "timestamp_period_ns=%.6f" in JNI
assert "context->computeTimestampValidBits = timingStats.compute_timestamp_valid_bits;" in JNI
assert "values[0] = 3; // JNI snapshot schema version" in JNI
assert "constexpr jsize headerFields = 7;" in JNI
assert "constexpr jsize stageFields = 5;" in JNI
assert "values[6] = stats.stage_count;" in JNI
assert "values[1] = stats.compute_timestamps_supported ? 1 : 0;" in JNI
assert "values[4] = stats.compute_timestamp_valid_bits;" in JNI
assert "values[2] = static_cast<jlong>(stats.timestamp_period_ns * 1000.0 + 0.5);" in JNI
assert "values[3] = stats.timestamp_compute_and_graphics ? 1 : 0;" in JNI
assert "vulkanTimestampSupported" in JNI and "compute_queue_timestampValidBits_zero" in JNI
assert "pending_or_dropped" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowDiagnostics.kt").read_text()
for stage in ("motionFine", "motionMiddle", "motionCoarse"):
    assert f"{stage} = stages[" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowDiagnostics.kt").read_text()
assert "Vulkan/libplacebo timestamp queries" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text()
assert "GLES_EXT_disjoint_timer_query staging copy" in (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/controls/PlayerControls.kt").read_text()
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
assert "LOCAL_MODULE := libudfread" in RUNTIME_BUILD
assert "LOCAL_SRC_FILES := $(PREFIX)/lib/libudfread.so" in RUNTIME_BUILD
assert "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv udfread placebo" in RUNTIME_BUILD
assert '"libplacebo.so"' in AAR_PACKAGE
assert "dynamic_defined_symbols" in AAR_PACKAGE
assert "pl_vulkan_release_ex" in AAR_PACKAGE

# MPV Flow keeps Vulkan rendering but can dispatch its motion/synthesis kernels through QCOM OpenCL.
assert "forceForMpvFlow = flowRequiresVulkan" in MPV_VIEW
assert 'gpuApi == "vulkan"' in POLICY
assert '"requires_gpu_next_vulkan"' in MPV_VIEW
assert "requires_gpu_next_opengl" not in MPV_VIEW
assert "from=opencl-qcom to=vulkan-spirv" in GPU
assert "renderer_api=vulkan compute_api=%s" in MPV_PATCH
assert "backend=opengl-es" not in MPV_PATCH
assert "gpu->glsl.vulkan" in GPU
assert 'PlaybackSession.setOptionString("display-fps-override", displayRefreshRate.hz.toString())' in MPV_VIEW
assert "display_refresh_source=" in MPV_VIEW
for marker in (
    "FlowOutputDecisionCounters",
    "outputDecisionTotals=",
    "motionGridStepSourcePxEstimate=",
    "pixelArtifactQuality=unmeasured_vulkan_ssbo_readback_disabled",
    "FLOW_OUTPUT_DIAGNOSTIC_LOG_MIN_INTERVAL_NS = 250_000_000L",
):
    assert marker in SINK, f"Media3 Flow must expose bounded decision and spatial-risk diagnostics: {marker}"
assert "estimateFlowMotionGridStepSourcePx" in FLOW_DIAGNOSTICS
assert "event=player_health" in MPV_VIEW
for marker in (
    "hwdec-current",
    "decoder-frame-drop-count",
    "frame-drop-count",
    "synthesis_rate_limited_callbacks",
    "render_cpu_avg_ms",
    "motion_grid_step_source_px_estimate",
    "pixelArtifactQuality=unmeasured_native_pixel_coverage_unavailable",
):
    assert marker in MPV_VIEW, f"MPV Flow health summary is missing a decoder/cadence/artifact-risk signal: {marker}"
print("Media3 Flow caps interpolation storage at 1080p; MPV Flow defaults to 1080p synthesis for larger sources, keeps full-resolution fallback for unsupported, paused, and still content, bounded motion analysis, async ownership handoffs, and measured output cadence")
