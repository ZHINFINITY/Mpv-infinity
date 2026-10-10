#!/usr/bin/env python3
"""Guard the shared Vulkan compute architecture used by MPV and Native Media3 Flow."""
from __future__ import annotations

import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SINK = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(encoding="utf-8")
NATIVE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVulkanNative.kt").read_text(encoding="utf-8")
NATIVE_ENGINE = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/NativeMedia3Engine.kt").read_text(encoding="utf-8")
JNI = (ROOT / "app/src/main/cpp/media3flow_vulkan_jni.cpp").read_text(encoding="utf-8")
GPU = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
POLICY = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/RendererBackendPolicy.kt").read_text(encoding="utf-8")
MPV_PATCH = (ROOT / "app/src/main/cpp/mpvflow/mpvflow-vo-gpu-next.patch").read_text(encoding="utf-8")

# Native Media3 may use GLES for decoder SurfaceTexture capture and final display only.
for marker in ("copyExternalTexture(", "drawTexture(", "GLES20.glFinish()", "interpolation=Vulkan"):
    assert marker in SINK, f"Native Media3 must retain its capture/display-only GLES stage: {marker}"
for forbidden in (
    "GLES31", "#version 310 es", "glDispatchCompute", "glBindImageTexture", "glMemoryBarrier",
    "FlowCoverageBuffer", "lumaProgram", "flowProgram", "synthProgram",
):
    assert forbidden not in SINK, f"Obsolete GLES interpolation compute remains in Media3 sink: {forbidden}"

# Both routes dispatch through the exact same libplacebo-backed Vulkan flow implementation.
for marker in ("nativePrepareFrame(", "nativeAnalyzePair(", "nativeSynthesize(", "nativeCreateFrameImage("):
    assert marker in SINK, f"Media3 sink is missing its Vulkan JNI dispatch: {marker}"
for marker in (
    "pl_vulkan_create(",
    "pl_vulkan_wrap(",
    "pl_vulkan_release_ex(",
    "pl_vulkan_hold_ex(",
    "VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID",
    "VK_QUEUE_FAMILY_FOREIGN_EXT",
    "mpvflow_gpu_create(",
    "mpvflow_gpu_prepare_input(",
    "mpvflow_gpu_analyze_pair(",
    "mpvflow_gpu_synthesize(",
):
    assert marker in JNI, f"Vulkan HardwareBuffer bridge is missing required contract: {marker}"
assert "external fun nativeCreateContext" in NATIVE
assert "external fun nativeAnalyzePair" in NATIVE
assert "media3FlowSink?.takeIf { it.isPreflightAvailable() }" in NATIVE_ENGINE

# MPV Flow is Vulkan-only, automatically selects gpu-next/Vulkan, and never advertises GLES compute.
assert "forceForMpvFlow = flowRequiresVulkan" in MPV_VIEW
assert 'gpuApi == "vulkan"' in POLICY
assert '"requires_gpu_next_vulkan"' in MPV_VIEW
assert "requires_gpu_next_opengl" not in MPV_VIEW
assert "backend=vulkan-spirv" in MPV_PATCH
assert "backend=opengl-es" not in MPV_PATCH
assert "gpu->glsl.vulkan" in GPU
print("Media3 and MPV Flow share Vulkan/SPIR-V compute; GLES is limited to Media3 surface capture/display")
