#!/usr/bin/env python3
"""Guard the Android MPVFlow native compiler and Vulkan build configuration."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def mpv_module_level(module: str, option: str) -> str:
    """Model MPV's ordered all/exact-or-parent-prefix msg-level rules."""
    level = "status"
    for rule in option.split(","):
        selector, value = rule.split("=", 1)
        if selector == "all" or module == selector or module.startswith(selector + "/"):
            level = value
    return level


def modeled_mppl_log_create_threshold(parent_module: str, option: str) -> str:
    """Model pinned mppl_log_create: cutoff from parent `log`, callback on child."""
    # Pinned MPV video/out/placebo/utils.c uses
    # `.log_level = determine_pl_log_level(log)` and
    # `.log_priv = mp_log_new(tactx, log, "libplacebo")`.
    parent_mpv_level = mpv_module_level(parent_module, option)
    return {"info": "warn", "v": "info", "debug": "debug", "trace": "trace"}[parent_mpv_level]


def pl_log_emits(message_level: str, threshold: str) -> bool:
    verbosity = ["fatal", "err", "warn", "info", "debug", "trace"]
    return verbosity.index(message_level) <= verbosity.index(threshold)


ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / "scripts/prepare-mpvflow.py"
BUILD = ROOT / "scripts/build-mpvflow-mpv-runtime.sh"
VULKAN_MAPPER_PATCH = ROOT / "app/src/main/cpp/mpvflow/mpvflow-aimagereader-vulkan.patch"
VULKAN_MAPPER_DIR = ROOT / "app/src/main/cpp/mpvflow/mediacodec-vulkan"
VO_PATCH = ROOT / "app/src/main/cpp/mpvflow/mpvflow-vo-gpu-next.patch"
PLAYBACK_SESSION = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt"
MPV_VIEW = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt"
MPVFLOW_LOG_CONFIG = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MpvFlowLogConfig.kt"
APP_BUILD = ROOT / "app/build.gradle.kts"
MPV_CONF_POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/preferences/MpvConfigOverride.kt"
POLICY = ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/RendererBackendPolicy.kt"
POLICY_TEST = ROOT / "app/src/test/java/app/infinity/mpvz/ui/player/RendererBackendPolicyTest.kt"
DECODER_SCREEN = ROOT / "app/src/main/java/app/infinity/mpvz/ui/preferences/DecoderPreferencesScreen.kt"
STRING_RESOURCES = ROOT / "app/src/main/res/values/strings.xml"

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("prepare_mpvflow", PREPARE)
assert spec and spec.loader, "could not load MPVFlow preparation module"
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)

old_dependency = "vulkan = dependency('vulkan', version: '>= 1.3.238', required: vulkan_opt)"
new_dependency = "vulkan = dependency('vulkan', required: vulkan_opt)"
header_check = "cc.has_header_symbol('vulkan/vulkan_core.h', 'VK_VERSION_1_3', dependencies: vulkan)"
meson = f"before\n{old_dependency}\n{header_check}\nafter\n"
patched = prepare.enable_android_vulkan_dependency(meson)
assert old_dependency not in patched, "Android build must not require missing Vulkan pkg-config version metadata"
assert new_dependency in patched, "Android Vulkan dependency must remain required and validate NDK headers"
assert header_check in patched, "Android build must retain the Vulkan 1.3 header-symbol requirement"
assert patched.startswith("before\n") and patched.endswith("after\n"), "dependency patch must preserve adjacent MPV Meson content"
assert prepare.enable_android_vulkan_dependency(patched) == patched, "MPV Vulkan preparation must be safe to repeat after dependency compilation"

for bad_meson in (
    "no Vulkan dependency here\n",
    f"{old_dependency}\n{old_dependency}\n",
    f"{old_dependency}\n",
):
    try:
        prepare.enable_android_vulkan_dependency(bad_meson)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected MPV Vulkan source changes must fail closed")

vulkan_context = (
    "before\n"
    "    const char *opt_extensions[] = {\n"
    "        VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME,\n"
    "    };\n"
    "after\n"
)
patched_vulkan_context = prepare.enable_android_external_memory_extensions(vulkan_context)
assert '"VK_ANDROID_external_memory_android_hardware_buffer"' in patched_vulkan_context
assert '"VK_KHR_external_semaphore_fd"' in patched_vulkan_context
assert '"VK_EXT_queue_family_foreign"' in patched_vulkan_context
assert patched_vulkan_context.startswith("before\n") and patched_vulkan_context.endswith("after\n")
assert prepare.enable_android_external_memory_extensions(patched_vulkan_context) == patched_vulkan_context, (
    "Android Vulkan interop extensions must be requested idempotently"
)
try:
    prepare.enable_android_external_memory_extensions("no optional extension list\n")
except SystemExit:
    pass
else:
    raise AssertionError("unexpected MPV Vulkan extension-list changes must fail closed")

mailbox_context = """\
    if (p->opts->swap_mode >= 0) // user override
        pl_params.present_mode = p->opts->swap_mode;
    vk->swapchain = pl_vulkan_create_swapchain(vk->vulkan, &pl_params);
"""
patched_mailbox_context = prepare.enable_mailbox_suboptimal_tolerance(mailbox_context)
mailbox_policy = "pl_params.present_mode == VK_PRESENT_MODE_MAILBOX_KHR;"
assert mailbox_policy in patched_mailbox_context
assert patched_mailbox_context.index("pl_params.present_mode = p->opts->swap_mode;") < patched_mailbox_context.index(
    "pl_params.allow_suboptimal ="
) < patched_mailbox_context.index("pl_vulkan_create_swapchain"), (
    "the effective user-selected present mode must determine suboptimal tolerance before swapchain creation"
)
assert prepare.enable_mailbox_suboptimal_tolerance(patched_mailbox_context) == patched_mailbox_context, (
    "mailbox suboptimal preparation must be safe to repeat"
)
assert "pl_params.allow_suboptimal =\n        pl_params.present_mode == VK_PRESENT_MODE_MAILBOX_KHR;" in patched_mailbox_context, (
    "only the effective Mailbox request may opt into suboptimal tolerance; FIFO and other modes remain false"
)
for bad_mailbox_context in (
    "no Vulkan swap-mode override here\n",
    patched_mailbox_context.replace(mailbox_policy, "false;"),
):
    try:
        prepare.enable_mailbox_suboptimal_tolerance(bad_mailbox_context)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected MPV swap-mode source changes must fail closed")

qcom_vulkan_context = """\
    const char *opt_extensions[] = {
        VK_NV_OPTICAL_FLOW_EXTENSION_NAME,
    };
    VkPhysicalDeviceZeroInitializeWorkgroupMemoryFeaturesKHR zero_init_feature = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ZERO_INITIALIZE_WORKGROUP_MEMORY_FEATURES_KHR,
    };
    VkPhysicalDeviceOpticalFlowFeaturesNV optical_flow_feature = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_OPTICAL_FLOW_FEATURES_NV,
        .pNext = &zero_init_feature,
        .opticalFlow = true,
    };
"""
patched_qcom_context = prepare.enable_qcom_image_processing(qcom_vulkan_context)
for marker in (
    "VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME",
    "VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME",
    "VkPhysicalDeviceImageProcessingFeaturesQCOM",
    "VkPhysicalDeviceImageProcessing2FeaturesQCOM",
    "textureBlockMatch = true",
    "textureBlockMatch2 = true",
    "&image_processing_feature",
    "MPVFLOW_QCOM_IMAGE_PROCESSING_FEATURES",
):
    assert marker in patched_qcom_context, f"MPV QCOM enablement patch is missing {marker!r}"
assert prepare.enable_qcom_image_processing(patched_qcom_context) == patched_qcom_context
for bad_qcom_context in (
    "no optional extension list or feature chain\n",
    qcom_vulkan_context + qcom_vulkan_context,
    patched_qcom_context.replace("textureBlockMatch2 = true", "textureBlockMatch2 = false"),
):
    try:
        prepare.enable_qcom_image_processing(bad_qcom_context)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected MPV QCOM feature-chain changes must fail closed")

placebo_meson = (
    "spirv = cxx.find_library('SPIRV', required: required, static: static, dirs: vulkan_lib_dirs)\n"
    "glslang_deps += spirv\n"
    "glslang_deps += cxx.find_library('glslang', required: required, static: static)\n"
)
patched_placebo_meson = prepare.enable_android_glslang_library_search(placebo_meson)
assert "cxx.find_library('glslang', required: required, static: static)" not in patched_placebo_meson, (
    "Android glslang lookup must not rely on compiler-default library directories"
)
assert "cxx.find_library('glslang', required: required, static: static, dirs: vulkan_lib_dirs)" in patched_placebo_meson, (
    "Android glslang lookup must use the same Vulkan SDK library directory as SPIRV"
)
assert "dirs: vulkan_lib_dirs" in patched_placebo_meson.splitlines()[0], (
    "the existing SPIRV Vulkan SDK lookup must remain intact"
)
assert prepare.enable_android_glslang_library_search(patched_placebo_meson) == patched_placebo_meson, (
    "libplacebo glslang preparation must be safe to repeat after dependency compilation"
)
for bad_placebo_meson in (
    "no glslang lookup here\n",
    placebo_meson + placebo_meson.splitlines()[-1] + "\n",
    placebo_meson.replace(", dirs: vulkan_lib_dirs", ""),
):
    try:
        prepare.enable_android_glslang_library_search(bad_placebo_meson)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo compiler lookup changes must fail closed")

timer_source = (
    "// Gives us enough queries for 8 results\n#define QUERY_POOL_SIZE 16\n"
    "struct pl_timer_t { atomic_uint_fast8_t pending; // bitmask of queries that are still running\n};\n"
    "static inline uint_fast8_t timer_bit(int index)\n"
)
patched_timer_source = prepare.enlarge_libplacebo_timer_ring(timer_source)
assert "#define QUERY_POOL_SIZE 64" in patched_timer_source, "the Vulkan timer ring must retain 32 complete results"
assert "atomic_uint_fast32_t pending" in patched_timer_source, "the pending mask must represent all 32 timer slots"
assert "static inline uint_fast32_t timer_bit(int index)" in patched_timer_source, "timer bits must use the widened mask type"
assert prepare.enlarge_libplacebo_timer_ring(patched_timer_source) == patched_timer_source, (
    "libplacebo timer-ring preparation must be safe to repeat"
)
for bad_timer_source in (
    timer_source.replace("QUERY_POOL_SIZE 16", "QUERY_POOL_SIZE 32"),
    timer_source + "// Gives us enough queries for 8 results\n#define QUERY_POOL_SIZE 16\n",
    timer_source.replace("atomic_uint_fast8_t pending", "atomic_uint_fast16_t pending"),
    patched_timer_source.replace("uint_fast32_t timer_bit", "uint_fast16_t timer_bit"),
):
    try:
        prepare.enlarge_libplacebo_timer_ring(bad_timer_source)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo timer-ring layout must fail closed")

wsi_header = "PL_API bool pl_vulkan_swapchain_suboptimal(pl_swapchain sw);\n"
patched_wsi_header = prepare.add_libplacebo_swapchain_diagnostics_api(wsi_header)
assert "PL_API bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled);" in patched_wsi_header
assert "PL_API bool pl_vulkan_swapchain_get_diagnostics(" in patched_wsi_header
assert "int *created_mode, int *requested_depth" in patched_wsi_header
assert prepare.add_libplacebo_swapchain_diagnostics_api(patched_wsi_header) == patched_wsi_header
legacy_wsi_header = wsi_header + "PL_API bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled);\n"
upgraded_wsi_header = prepare.add_libplacebo_swapchain_diagnostics_api(legacy_wsi_header)
assert upgraded_wsi_header.count("PL_API bool pl_vulkan_swapchain_set_diagnostics(") == 1
assert "PL_API bool pl_vulkan_swapchain_get_diagnostics(" in upgraded_wsi_header
for bad_wsi_header in ("no swapchain declaration here\n", wsi_header + wsi_header):
    try:
        prepare.add_libplacebo_swapchain_diagnostics_api(bad_wsi_header)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo Vulkan API changes must fail closed")
stub_source = "bool pl_vulkan_swapchain_suboptimal(pl_swapchain sw)\n{\n    pl_unreachable();\n}\n"
patched_stub_source = prepare.add_libplacebo_swapchain_diagnostics_stub(stub_source)
assert "bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled)" in patched_stub_source
assert "bool pl_vulkan_swapchain_get_diagnostics(" in patched_stub_source
assert prepare.add_libplacebo_swapchain_diagnostics_stub(patched_stub_source) == patched_stub_source
legacy_stub_source = stub_source + "bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled)\n{\n    pl_unreachable();\n}\n"
upgraded_stub_source = prepare.add_libplacebo_swapchain_diagnostics_stub(legacy_stub_source)
assert upgraded_stub_source.count("bool pl_vulkan_swapchain_set_diagnostics(") == 1
assert "bool pl_vulkan_swapchain_get_diagnostics(" in upgraded_stub_source
for bad_stub_source in ("unrecognized no-Vulkan stubs\n", stub_source + stub_source):
    try:
        prepare.add_libplacebo_swapchain_diagnostics_stub(bad_stub_source)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo no-Vulkan stubs must fail closed")
formats_source = (
    'static const struct vk_format vk_formats[] = {\n'
    '    {VK_FORMAT_R16G16_SINT,           REGFMT("rg16i",    2, 16, SINT)},\n'
    '};\n'
)
patched_formats_source = prepare.enable_nv_optical_flow_vector_format(formats_source)
for marker in (
    "#ifdef VK_NV_optical_flow",
    'VK_FORMAT_R16G16_S10_5_NV, REGFMT("rg16sfix5", 2, 16, SINT)',
    "MPVFLOW_NV_OPTICAL_FLOW_SFIXED5_FORMAT",
):
    assert marker in patched_formats_source, f"libplacebo optical format mapping is missing {marker!r}"
assert prepare.enable_nv_optical_flow_vector_format(patched_formats_source) == patched_formats_source
for bad_formats_source in (
    "no signed-integer format table here\n",
    formats_source + formats_source,
    patched_formats_source.replace("rg16sfix5", "rg16i"),
):
    try:
        prepare.enable_nv_optical_flow_vector_format(bad_formats_source)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo format-table change must fail closed")
sampleability_anchor = (
    "        if (fmt->caps) {\n"
    '            fmt->glsl_type = pl_var_glsl_type_name(pl_var_from_fmt(fmt, ""));\n'
    "            pl_assert(fmt->glsl_type);\n"
    "        }\n"
)
sampleability_source = "before\n" + sampleability_anchor + "after\n"
patched_sampleability_source = prepare.enable_nv_optical_flow_sampleability(sampleability_source)
for marker in (
    "VK_FORMAT_R16G16_S10_5_NV",
    'strcmp(vk->exts.elem[i], "VK_NV_optical_flow")',
    "VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV",
    "VK_IMAGE_USAGE_SAMPLED_BIT",
    "vk->GetPhysicalDeviceImageFormatProperties2(",
    "fmt->caps |= PL_FMT_CAP_SAMPLEABLE",
    "MPVFLOW_NV_OPTICAL_FLOW_SFIXED5_SAMPLEABILITY",
):
    assert marker in patched_sampleability_source, f"NV sampleability must be gated by the extension-aware image query: missing {marker!r}"
assert prepare.enable_nv_optical_flow_sampleability(patched_sampleability_source) == patched_sampleability_source
for bad_sampleability_source in (
    "format capability setup changed\n",
    sampleability_source + sampleability_anchor,
    patched_sampleability_source.replace("VK_IMAGE_USAGE_SAMPLED_BIT", "VK_IMAGE_USAGE_STORAGE_BIT"),
):
    try:
        prepare.enable_nv_optical_flow_sampleability(bad_sampleability_source)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo sampleability code must fail closed")
wsi_source = """\
struct priv {
    bool has_swapchain_maintenance1;
    struct pl_color_repr color_repr;
};
static const struct pl_sw_fns vulkan_swapchain;


static bool map_color_space(VkColorSpaceKHR space, struct pl_color_space *out)
{
    return true;
}
static bool update_swapchain_info(struct priv *p)
{
    VkSurfaceCapabilitiesKHR caps = {0};
caps_ready:
    // Check for hidden/invisible window
    return true;
}
static void init_priv(struct priv *p)
{
    pl_mutex_init(&p->lock);
    p->impl = vulkan_swapchain;
}
static bool vk_sw_recreate(pl_swapchain sw, int w, int h)
{
    VkResult res = vk->CreateSwapchainKHR(vk->dev, &sinfo, PL_VK_ALLOC, &current->swapchain);
    PL_VK_ASSERT(res, "vk->CreateSwapchainKHR(...)");

    // Get the new swapchain images
    VK(vk->GetSwapchainImagesKHR(vk->dev, current->swapchain, &num_images, NULL));
    VK(vk->GetSwapchainImagesKHR(vk->dev, current->swapchain, &num_images, current->vkimages.elem));
}
static bool vk_sw_start_frame(pl_swapchain sw)
{
    struct priv *p = PL_PRIV(sw);
    struct vk_ctx *vk = p->vk;
    pl_mutex_lock(&p->lock);

    bool recreate = !p->current || p->needs_recreate;
    if (p->suboptimal && !p->params.allow_suboptimal)
        recreate = true;
    if (recreate && !vk_sw_recreate(sw, 0, 0)) {
        pl_mutex_unlock(&p->lock);
        return false;
    }

    for (int attempts = 0; attempts < 2; attempts++) {
        uint32_t imgidx = 0;
        VkResult res = vk->AcquireNextImageKHR(vk->dev, p->current->swapchain, UINT64_MAX,
                                               sem_in, VK_NULL_HANDLE, &imgidx);
        switch (res) {
        case VK_SUBOPTIMAL_KHR:
            p->suboptimal = true;
            // fall through
        case VK_SUCCESS:
            p->current->last_imgidx = imgidx;
            if (p->current->fences_out.num > 0) {
                VkFence *pfence = &p->current->fences_out.elem[imgidx];
                vk->WaitForFences(vk->dev, 1, pfence, VK_TRUE, UINT64_MAX);
                vk->ResetFences(vk->dev, 1, pfence);
            }
            if (!vk_sw_image_init(sw, imgidx))
                goto error;
            return true;
        case VK_ERROR_OUT_OF_DATE_KHR: {
            // In these cases try recreating the swapchain
            if (!vk_sw_recreate(sw, 0, 0)) {
                pl_mutex_unlock(&p->lock);
                return false;
            }
            continue;
        }
        }
    }
error:
    pl_mutex_unlock(&p->lock);
    return false;
}
static bool vk_sw_submit_frame(pl_swapchain sw)
{
    int qidx = cmd->qindex;
    if (!CMD_SUBMIT(&cmd)) {
        pl_mutex_unlock(&p->lock);
        return false;
    }
    vk->lock_queue(vk->queue_ctx, pool->qf, qidx);
    VkResult res = vk->QueuePresentKHR(queue, &pinfo);
    vk->unlock_queue(vk->queue_ctx, pool->qf, qidx);
    switch (res) {
    case VK_SUBOPTIMAL_KHR:
        p->suboptimal = true;
        // fall through
    case VK_SUCCESS:
        return true;
    case VK_ERROR_OUT_OF_DATE_KHR:
        // We can silently ignore this error, since the next start_frame will
        // recreate the swapchain automatically.
        return true;
    }
}
static bool vk_sw_resize(pl_swapchain sw, int *width, int *height)
{
    bool width_changed = *width && *width != p->cur_width,
         height_changed = *height && *height != p->cur_height;
    if (p->suboptimal || p->needs_recreate || width_changed || height_changed)
        ok = vk_sw_recreate(sw, *width, *height);
}
bool pl_vulkan_swapchain_suboptimal(pl_swapchain sw)
{
    struct priv *p = PL_PRIV(sw);
    return p->suboptimal;
}
"""
prepare.validate_libplacebo_suboptimal_recovery(wsi_source)
for bad_wsi_recovery in (
    wsi_source.replace("case VK_ERROR_OUT_OF_DATE_KHR", "case VK_SUCCESS"),
    wsi_source.replace("width_changed || height_changed", "false"),
):
    try:
        prepare.validate_libplacebo_suboptimal_recovery(bad_wsi_recovery)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected pinned WSI recovery changes must fail closed")
patched_wsi_source = prepare.instrument_libplacebo_swapchain(wsi_source)
prepare.validate_libplacebo_suboptimal_recovery(patched_wsi_source)
bad_instrumented_recovery = patched_wsi_source.replace(
    "bool flow_retry_recreate_ok = vk_sw_recreate(sw, 0, 0);",
    "bool flow_retry_recreate_ok = false;",
    1,
)
try:
    prepare.validate_libplacebo_suboptimal_recovery(bad_instrumented_recovery)
except SystemExit:
    pass
else:
    raise AssertionError("repeated preparation must fail closed if timed out-of-date recreation is lost")
for recovery_marker in (
    "if (p->suboptimal && !p->params.allow_suboptimal)\n        recreate = true;",
    "case VK_SUBOPTIMAL_KHR:\n            p->suboptimal = true;\n            // fall through\n        case VK_SUCCESS:",
    "case VK_ERROR_OUT_OF_DATE_KHR:\n        // We can silently ignore this error, since the next start_frame will\n        // recreate the swapchain automatically.\n        return true;",
    "if (p->suboptimal || p->needs_recreate || width_changed || height_changed)\n        ok = vk_sw_recreate(sw, *width, *height);",
):
    assert recovery_marker in patched_wsi_source, f"pinned WSI recovery changed: {recovery_marker!r}"
assert "case VK_ERROR_OUT_OF_DATE_KHR: {" in patched_wsi_source
assert "bool flow_retry_recreate_ok = vk_sw_recreate(sw, 0, 0);" in patched_wsi_source
assert "if (!flow_retry_recreate_ok) {" in patched_wsi_source and "continue;" in patched_wsi_source, (
    "the timed instrumentation must preserve out-of-date recreation failure handling and retry"
)

def should_recreate_on_start(current: bool, needs_recreate: bool, suboptimal: bool, allow_suboptimal: bool) -> bool:
    recreate = not current or needs_recreate
    if suboptimal and not allow_suboptimal:
        recreate = True
    return recreate

assert not should_recreate_on_start(True, False, True, True), "Mailbox must tolerate VK_SUBOPTIMAL_KHR at frame start"
assert should_recreate_on_start(True, False, True, False), "non-Mailbox/default mode must retain suboptimal recreation"
assert should_recreate_on_start(True, True, False, True), "needs_recreate must still recreate under Mailbox"
assert should_recreate_on_start(False, False, False, True), "missing swapchains must still be created under Mailbox"

def should_recreate_on_resize(suboptimal: bool, needs_recreate: bool, width_changed: bool, height_changed: bool) -> bool:
    return suboptimal or needs_recreate or width_changed or height_changed

assert should_recreate_on_resize(False, False, True, False), "surface width changes must still recreate under Mailbox"
assert should_recreate_on_resize(False, False, False, True), "surface height changes must still recreate under Mailbox"
assert should_recreate_on_resize(True, False, False, False), "explicit resize must clear a tolerated suboptimal swapchain"

for marker in (
    "atomic_bool flow_diagnostics",
    "VkPresentModeKHR flow_created_present_mode",
    "uint32_t flow_created_image_count",
    "flow_created_info_valid, flow_created_info_reported",
    "flow_report_created_swapchain(p);",
    "create_min_images=%u",
    "actual_images=%u",
    "uint32_t flow_surface_min_image_count, flow_surface_max_image_count",
    "surface_min_images=%u surface_max_images=%u",
    "p->flow_surface_min_image_count = caps.minImageCount;",
    "p->flow_surface_max_image_count = caps.maxImageCount;",
    "event=wsi_swapchain_created",
    'FLOW_WSI_ACQUIRE, "vkAcquireNextImageKHR"',
    'FLOW_WSI_FENCE_WAIT, "present_fence_wait"',
    'FLOW_WSI_PRESENT, "vkQueuePresentKHR"',
    "bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled)",
    "bool pl_vulkan_swapchain_get_diagnostics(",
):
    assert marker in patched_wsi_source, f"libplacebo WSI diagnostic instrumentation is missing {marker!r}"
assert "metric=host_call_duration_not_scanout" in patched_wsi_source
assert "if (!atomic_load_explicit(&p->flow_diagnostics, memory_order_relaxed))" in patched_wsi_source
assert "bool first_sample = !p->flow_diag_last_report[stage];" in patched_wsi_source
assert "if (window_s < 5.0 && !first_sample && !low_frequency_stage)" in patched_wsi_source, (
    "WSI timings must emit their first sample, then remain rate-limited rather than log per frame"
)
assert "acquire_semaphore_signal=%s" not in patched_wsi_source, (
    "vkAcquireNextImageKHR returning VK_SUCCESS does not prove its semaphore has signaled at host return"
)
assert patched_wsi_source.index("p->flow_created_present_mode = sinfo.presentMode;") < patched_wsi_source.index(
    "flow_report_created_swapchain(p);"
), "the created swapchain mode must be stored before it is reported"
assert patched_wsi_source.index("p->flow_created_image_count = num_images;") < patched_wsi_source.index(
    "flow_report_created_swapchain(p);"
), "actual image count must be stored before the created swapchain is reported"
assert patched_wsi_source.index("p->flow_surface_min_image_count = caps.minImageCount;") < patched_wsi_source.index(
    "// Check for hidden/invisible window"
), "surface image-count limits must be captured at the shared caps_ready path"
diagnostic_setter = patched_wsi_source.split(
    "bool pl_vulkan_swapchain_set_diagnostics(pl_swapchain sw, bool enabled)", 1
)[1]
assert "if (enabled)\n        flow_report_created_swapchain(p);" in diagnostic_setter, (
    "enabling diagnostics after creation must report the effective swapchain mode"
)
assert prepare.instrument_libplacebo_swapchain(patched_wsi_source) == patched_wsi_source
for call in (
    "vk->AcquireNextImageKHR(vk->dev, p->current->swapchain, UINT64_MAX,",
    "vk->WaitForFences(vk->dev, 1, pfence, VK_TRUE, UINT64_MAX)",
):
    assert patched_wsi_source.count(call) == wsi_source.count(call) == 1, (
        f"WSI instrumentation must preserve the original infinite-timeout API call: {call}"
    )
assert "VK_TRUE, UINT64_MAX" in patched_wsi_source and "vk->ResetFences(vk->dev, 1, pfence);" in patched_wsi_source, (
    "WSI instrumentation must preserve the original present-fence wait/reset sequence"
)
for bad_wsi_source in ("unrecognized Vulkan swapchain source\n", wsi_source + "\n" + wsi_source):
    try:
        prepare.instrument_libplacebo_swapchain(bad_wsi_source)
    except SystemExit:
        pass
    else:
        raise AssertionError("unexpected libplacebo swapchain implementation must fail closed")

build = BUILD.read_text(encoding="utf-8")
prepare_source = PREPARE.read_text(encoding="utf-8")
mpv_view_source = MPV_VIEW.read_text(encoding="utf-8")
flow_log_config_source = MPVFLOW_LOG_CONFIG.read_text(encoding="utf-8")
qcom_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_vulkan_qcom.c").read_text(encoding="utf-8")
assert "MpvFlowLogConfig.messageLevelOption(" in mpv_view_source
assert 'PlaybackSession.setOptionString("msg-level", logLevel)' in mpv_view_source
assert "requested_msg_level=$logLevel" in mpv_view_source
assert 'const val FLOW_VERBOSE_DIAGNOSTICS = "all=info,vo/gpu-next=v"' in flow_log_config_source
assert "mpvFlowEnabled -> FLOW_VERBOSE_DIAGNOSTICS" in flow_log_config_source
child_only_policy = "all=info,vo/gpu-next/libplacebo=v"
parent_policy = "all=info,vo/gpu-next=v"
assert mpv_module_level("vo/gpu-next", child_only_policy) == "info"
assert modeled_mppl_log_create_threshold("vo/gpu-next", child_only_policy) == "warn"
assert not pl_log_emits("info", modeled_mppl_log_create_threshold("vo/gpu-next", child_only_policy)), (
    "the previous child-only override leaves mppl_log_create's parent-derived PL_INFO filtered"
)
assert mpv_module_level("vo/gpu-next", parent_policy) == "v"
assert mpv_module_level("vo/gpu-next/libplacebo", parent_policy) == "v"
parent_threshold = modeled_mppl_log_create_threshold("vo/gpu-next", parent_policy)
assert parent_threshold == "info" and pl_log_emits("info", parent_threshold), (
    "the parent gpu-next override must make pinned mppl_log_create pass WSI PL_INFO records"
)
logger_option_call = 'PlaybackSession.setOptionString("msg-level", logLevel)'
vo_option_call = "PlaybackSession.setVideoOutput(backend.vo)"
assert mpv_view_source.index(logger_option_call) < mpv_view_source.index(vo_option_call), (
    "Flow must apply the parent logger threshold before the gpu-next VO/libplacebo logger is created"
)
assert "event=logger_config" in mpv_view_source and "msg_level_apply_rc=" in mpv_view_source
flow_vo_patch_source = VO_PATCH.read_text(encoding="utf-8")
assert "logger_policy=all=info,vo/gpu-next=v" in flow_vo_patch_source
assert "parent_msg_level=%d" in flow_vo_patch_source
assert "libplacebo_info_enabled=%d" in flow_vo_patch_source
assert "diagnostics_gate_enabled=%d" in flow_vo_patch_source
assert "first_runtime_wsi_sample=next_start_frame" in flow_vo_patch_source
toggle_offset = flow_vo_patch_source.index("event=wsi_diagnostics_toggle engine=mpv")
created_offset = flow_vo_patch_source.index("event=wsi_swapchain_created engine=mpv")
first_start_frame_offset = flow_vo_patch_source.index(
    "+        should_draw && pl_swapchain_start_frame(p->sw, &swframe);"
)
assert toggle_offset < created_offset < first_start_frame_offset, (
    "the enabled gate and effective created swapchain mode/image count must be reported before the first runtime WSI call"
)
assert "if (qcom->dispatches == 1 || qcom->dispatches % 120 == 0)" in qcom_source
assert "if (qcom->dispatches % 120 == 0)" in qcom_source, (
    "QCOM timing reports must remain periodic, not add per-dispatch log overhead"
)
assert 'qcom_log(qcom->gpu, PL_LOG_INFO,\n             "MPVFLOW_DIAGNOSTIC event=qcom_block_match_image state=created' in qcom_source, (
    "QCOM image-sharing diagnostics must be visible at Flow verbosity and occur only during image creation"
)
assert 'VO_PATCH = VENDOR / "mpvflow-vo-gpu-next.patch"' in prepare_source, (
    "MPV source preparation must keep the custom VO patch path explicit"
)
assert '"--input", str(VO_PATCH)' in prepare_source, (
    "MPV source preparation must never derive its patch input from the positional native-source list"
)
assert '"--input", str(required[2])' not in prepare_source, (
    "adding OpenCL sources must not silently redirect the mpv patch command"
)
assert 'AIMAGE_VULKAN_PATCH = VENDOR / "mpvflow-aimagereader-vulkan.patch"' in prepare_source
assert '"--input", str(AIMAGE_VULKAN_PATCH)' in prepare_source
assert "vulkan_context = enable_mailbox_suboptimal_tolerance(vulkan_context)" in prepare_source, (
    "the measured mailbox suboptimal fix must be applied by the pinned MPV preparation pipeline"
)
assert prepare_source.count('"mpvflow_opencl_validation.h"') == 2, (
    "MPV native preparation must validate and copy the OpenCL synthesis validation header"
)
for marker in (
    'VENDOR / "mpvflow_vulkan_optical.c"',
    'VENDOR / "mpvflow_vulkan_optical.h"',
    'VENDOR / "mpvflow_vulkan_qcom.c"',
    'VENDOR / "mpvflow_vulkan_qcom.h"',
    'VENDOR / "mpvflow_qcom_seed.comp"',
    'VENDOR / "mpvflow_qcom_seed_spv.h"',
    '"mpvflow_vulkan_optical.c"',
    '"mpvflow_vulkan_optical.h"',
    '"mpvflow_vulkan_qcom.c"',
    '"mpvflow_vulkan_qcom.h"',
    '"mpvflow_qcom_seed.comp"',
    '"mpvflow_qcom_seed_spv.h"',
    '"video/out/mpvflow_vulkan_optical.c"',
    '"video/out/mpvflow_vulkan_qcom.c"',
    'placebo_formats_path = libplacebo_dir / "src/vulkan/formats.c"',
    "enable_nv_optical_flow_sampleability",
):
    assert marker in prepare_source, f"MPV native preparation must include optional optical integration: {marker!r}"
assert VULKAN_MAPPER_PATCH.is_file(), "the pinned MPV mapper compatibility patch must be vendored"
for source in (
    "hwdec_aimagereader.c",
    "hwdec_aimagereader_vk.c",
    "hwdec_aimagereader_vk_convert.c",
    "hwdec_aimagereader_vk.h",
    "hwdec_aimagereader_vk_private.h",
):
    assert (VULKAN_MAPPER_DIR / source).is_file(), f"missing AImageReader/Vulkan source {source}"
assert not (VULKAN_MAPPER_DIR / "hwdec_aimagereader_vk_direct.c").exists(), (
    "the pinned upstream libplacebo build must not include its unsupported direct-YCbCr wrapper"
)
prepare_call = 'python3 "$ROOT/scripts/prepare-mpvflow.py" --mpv-dir "$BUILDSCRIPTS/deps/mpv" --libplacebo-dir "$BUILDSCRIPTS/deps/libplacebo"'
assert prepare_call in build, "the native build must patch pinned libplacebo before compilation"
native_cmake = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
for source in (
    "mpvflow/mpvflow_vulkan_qcom.c",
    "mpvflow/mpvflow_vulkan_optical.c",
    "mpvflow/mpvflow_opencl.c",
    "mpvflow/mpvflow_opencl_vk_bridge.c",
):
    assert source in native_cmake, f"Native Media3 CMake target must compile the real OpenCL backend: {source}"
assert "\n        dl\n" in native_cmake, "Native Media3 must link the runtime dlopen dependency"
optical_adapter = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_vulkan_optical.c").read_text(encoding="utf-8")
optical_dispatch = optical_adapter.split(
    "bool mpvflow_vulkan_optical_dispatch(", 1
)[1].split("\nvoid mpvflow_vulkan_optical_destroy(", 1)[0]
for marker in (
    "available_command(optical)",
    "pl_vulkan_hold_ex",
    "VK_OPTICAL_FLOW_EXECUTE_DISABLE_TEMPORAL_HINTS_BIT_NV",
    "vkQueueSubmit",
    "pl_vulkan_release_ex",
    "shader_quality_checks=enabled",
):
    assert marker in optical_dispatch, f"NV optical dispatch must preserve synchronized validated fallback: missing {marker!r}"
assert "vkQueueWaitIdle" not in optical_dispatch, "per-frame optical execution must remain nonblocking"
assert "optical->disabled = true" in optical_adapter, "fatal optional backend failures must disable NV and preserve ordinary Vulkan matching"
for marker in (
    "vk->get_proc_addr",
    "vkGetPhysicalDeviceProperties2KHR",
    "vkGetPhysicalDeviceImageFormatProperties2KHR",
):
    assert marker in optical_adapter, f"Android-safe Vulkan promoted-function dispatch is missing {marker!r}"
assert "vkGetPhysicalDeviceProperties2(optical->vk->phys_device" not in optical_adapter
assert "vkGetPhysicalDeviceImageFormatProperties2(" not in optical_adapter
vo_patch = VO_PATCH.read_text(encoding="utf-8")
for marker in (
    "event=swapchain_wait stage=pl_swapchain_start_frame",
    "event=swapchain_wait stage=swap_buffers",
    "metric=cpu_blocking_call_not_scanout",
    "submit_call_avg_ms=%.3f",
    "event=wsi_diagnostics_toggle engine=mpv",
    "event=wsi_swapchain_created engine=mpv",
    "pl_vulkan_swapchain_get_diagnostics",
    "actual_images=%u",
    "event=optical_flow_backend engine=mpv",
    "optical_flow_reason",
    '.diagnostic_engine = "mpv"',
    "qcom2_adapter_ready=%d",
    "qcom2_luma_pass_ready=%d",
    "event=qcom_runtime",
    "pair_attempts=%llu dispatches=%llu fallbacks=%llu",
    "mpvflow_gpu_get_qcom_diagnostics",
):
    assert marker in vo_patch, f"MPV Flow must distinguish real blocking swapchain waits from submit time: missing {marker!r}"
patch_parse = subprocess.run(
    ["git", "apply", "--numstat", str(VO_PATCH)],
    cwd=ROOT,
    check=False,
    capture_output=True,
    text=True,
)
assert patch_parse.returncode == 0, (
    f"MPV VO patch must be a valid unified diff:\n{patch_parse.stdout}{patch_parse.stderr}"
)
assert "+        target_fps >= 120 ? 448 : target_fps >= 96 ? 440 : 640;" in vo_patch, (
    "the MPV renderer must preserve the balanced 384-pixel 120-fps profile and the quality headroom"
)
rate_limit_header = ROOT / "app/src/main/cpp/mpvflow/mpvflow_rate_limit.h"
assert rate_limit_header.is_file(), "the shared MPVFlow output limiter must be vendored"
assert 'VENDOR / "mpvflow_rate_limit.h"' in prepare_source and \
       'shutil.copy2(VENDOR / "mpvflow_rate_limit.h", video_out_dir / "mpvflow_rate_limit.h")' in prepare_source, (
    "native preparation must stage the tested limiter header into MPV's video/out tree"
)
rate_gate_offset = vo_patch.index("mpvflow_rate_limiter_ready(&p->flow_rate_limiter")
rate_prepare_offset = vo_patch.index("flow_prepare_frame(vo, &mix, &flow_prepared, frame->still,")
rate_acquire_offset = vo_patch.index(
    "+        should_draw && pl_swapchain_start_frame(p->sw, &swframe);"
)
assert rate_gate_offset < rate_prepare_offset < rate_acquire_offset, (
    "the synthesis-rate gate must precede Flow preparation while source-frame acquisition stays enabled"
)
rate_submit_guard = vo_patch.index("if (p->next_opts->mpvflow && submitted)")
rate_commit_offset = vo_patch.index("mpvflow_rate_limiter_commit(&p->flow_rate_limiter")
assert rate_submit_guard < rate_commit_offset, "the rate interval may only be committed after successful swap submission"
assert "flow_pending_rate_limiter_timestamp_ns = (uint64_t) flow_render_start_ns;" in vo_patch
assert "p->flow_pending_rate_limiter_timestamp_ns," in vo_patch
assert "mpvflow_rate_limiter_commit(&p->flow_rate_limiter, (uint64_t) now_ns" not in vo_patch, (
    "rate admission must be committed at the same render-start timestamp, not swap completion"
)
flow_prepare_body = vo_patch.split("+static bool flow_prepare_input", 1)[1].split(
    "\n+static void flow_release_frame", 1
)[0]
crop_call = "+    apply_source_crop(&source_frame, p->src, vo->params->w, vo->params->h, fp);"
assert crop_call in flow_prepare_body, "MPVFlow must crop the decoder image before motion analysis"
assert flow_prepare_body.index(crop_call) < flow_prepare_body.index("pl_render_image("), (
    "the mapped AImage crop must be applied before copying source pixels into Flow textures"
)
flow_synthesis_body = vo_patch.split("+static void flow_prepare_frame", 1)[1].split(
    "\n+static bool flow_render_frame", 1
)[0]
paused_still_guard = "+    if (p->paused || source_is_still) {"
assert paused_still_guard in flow_synthesis_body, "paused and still frames must bypass MPVFlow analysis"
assert flow_synthesis_body.index(paused_still_guard) < flow_synthesis_body.index("mpvflow_gpu_analyze_pair(")
assert flow_synthesis_body.index(paused_still_guard) < flow_synthesis_body.index("mpvflow_gpu_synthesize(")
assert "flow_prepare_frame(vo, &mix, &flow_prepared, frame->still," in vo_patch
assert '+        const char *queue_state = p->paused ? "paused" :' in vo_patch, (
    "the paused state must not be hidden by empty-batch or still-frame diagnostics"
)

playback_session_source = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlaybackSession.kt").read_text(
    encoding="utf-8"
)
handoff_pause_body = playback_session_source.split("fun setPauseForEngineHandoff", 1)[1].split(
    "/** Atomically toggles pause", 1
)[0]
assert 'MPVLib.setPropertyBoolean("pause", paused)' in handoff_pause_body
assert "MpvConfigOverridePolicy.isOwnedByMpvConf" not in handoff_pause_body
assert "_state.value.phase != PlaybackPhase.LOADING" not in handoff_pause_body
player_activity_source = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/PlayerActivity.kt").read_text(
    encoding="utf-8"
)
handoff_source = player_activity_source.split("Freeze the outgoing renderer before changing surfaces", 1)[1].split(
    "if (currentUri != null", 1
)[0]
assert "setPauseForEngineHandoff(true)" in handoff_source
assert 'getPropertyBoolean("pause") == true' in handoff_source
assert "Engine handoff aborted because MPV pause was not acknowledged" in handoff_source
assert "setPauseForEngineHandoff(true)" in player_activity_source.split("nativeEngine.play(", 1)[0]

media3_flow_sink_source = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(
    encoding="utf-8"
)
flow_surface_initialize = media3_flow_sink_source.split("override fun initialize(sourceFormat: Format)", 1)[1].split(
    "override fun isInitialized()", 1
)[0]
flow_output_surface_callback = media3_flow_sink_source.split("override fun setOutputSurfaceInfo(", 1)[1].split(
    "override fun clearOutputSurfaceInfo()", 1
)[0]
surface_binding_policy = "Media3FlowOutputSurfacePolicy.shouldCreateEglWindowSurface("
assert surface_binding_policy in flow_output_surface_callback, (
    "the dormant Flow sink must not reserve the PlayerView SurfaceView before its renderer initializes"
)
assert flow_output_surface_callback.index(surface_binding_policy) < flow_output_surface_callback.index(
    "createWindowSurface()"
), "the surface ownership guard must run before EGL connects to the PlayerView BufferQueue"
assert "initialized = sinkInitialized" in flow_surface_initialize
assert flow_surface_initialize.index("initialized = sinkInitialized") < flow_surface_initialize.index(
    "createWindowSurface()"
), "initialize must publish Flow ownership before connecting its output EGL surface"

assert build.index("./include/ci.sh install") < build.index(prepare_call) < build.index("./include/ci.sh build"), (
    "the libplacebo search-path correction must run after dependency checkout and before native builds"
)
required_fragments = (
    "1062752a891c95b2bfeed9e356562d88f9df84ac",
    "-DCMAKE_TOOLCHAIN_FILE=",
    "-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28",
    "-DGLSLANG_ENABLE_INSTALL=ON -DGLSLANG_TESTS=OFF",
    "-DENABLE_GLSLANG_BINARIES=OFF -DENABLE_SPIRV=ON -DENABLE_SPVREMAPPER=OFF",
    "-DENABLE_HLSL=OFF -DENABLE_OPT=OFF -DENABLE_PCH=OFF",
    'export CPPFLAGS="${CPPFLAGS:-} -I$prefix_dir/include"',
    'export LDFLAGS="${LDFLAGS:-} -L$prefix_dir/lib -lc++"',
    "-Dglslang=enabled -Dvulkan-sdk=",
    "-Dvulkan=enabled",
    'export MPVFLOW_ROOT="$ROOT"',
    "libSPIRV.a libglslang.a libglslang-default-resource-limits.a",
    'MPV_BUILD_DIR="$BUILDSCRIPTS/deps/mpv/_build-arm64"',
    'MPV_DEPENDENCIES="$MPV_BUILD_DIR/meson-info/intro-dependencies.json"',
    'PLACEBO_PC="$MPV_PREFIX/pkgconfig/libplacebo.pc"',
    'PLACEBO_VERSION="$(sed -n \'s/^Version: *//p\' "$PLACEBO_PC")"',
    'readelf -d "$MPV_LIBRARY"',
    'python3 - "$MPV_DEPENDENCIES" "$PLACEBO_VERSION"',
    'dependency.get("name") == "libplacebo"',
    'actual_version != expected_version',
    'Verified MPV Meson libplacebo dependency version',
)
for fragment in required_fragments:
    assert fragment in build, f"native build must require Android Vulkan/SPIR-V support: missing {fragment!r}"
assert 'strings "$MPV_LIBRARY"' not in build, (
    "the native build must validate MPV's configured dependency, not search libmpv for the player-only version string"
)
version_guard_start = 'python3 - "$MPV_DEPENDENCIES" "$PLACEBO_VERSION" <<\'PY\'\n'
version_guard = build.split(version_guard_start, 1)[1].split("\nPY\n", 1)[0]
with tempfile.TemporaryDirectory(prefix="mpvflow-placebo-version-test-") as temporary:
    dependencies_file = Path(temporary) / "intro-dependencies.json"
    dependencies_file.write_text(
        json.dumps([{"name": "libplacebo", "version": "7.374.0"}]),
        encoding="utf-8",
    )
    matching = subprocess.run(
        [sys.executable, "-", str(dependencies_file), "7.374.0"],
        input=version_guard,
        check=False,
        capture_output=True,
        text=True,
    )
    assert matching.returncode == 0, f"matching MPV/libplacebo metadata must pass: {matching.stderr}"
    mismatching = subprocess.run(
        [sys.executable, "-", str(dependencies_file), "7.371.0"],
        input=version_guard,
        check=False,
        capture_output=True,
        text=True,
    )
    assert mismatching.returncode != 0, "a real MPV/libplacebo Meson version mismatch must fail closed"
    dependencies_file.write_text("[]\n", encoding="utf-8")
    missing = subprocess.run(
        [sys.executable, "-", str(dependencies_file), "7.374.0"],
        input=version_guard,
        check=False,
        capture_output=True,
        text=True,
    )
    assert missing.returncode != 0, "missing MPV libplacebo dependency metadata must fail closed"

session_source = PLAYBACK_SESSION.read_text(encoding="utf-8")
assert "fun surfaceFrameRateHintHz(): Double? = activeSurfaceFrameRateHintHz" in session_source
assert session_source.count("activeSurfaceFrameRateHintHz = null") >= 3, (
    "PlaybackSession must clear the retained Surface-rate hint on fresh initialization, failure, and teardown"
)
initialize_order = (
    "MPVLib.create(context.applicationContext)",
    "initOptions()",
    "MPVLib.init()",
    "postInitOptions()",
)
initialize_positions = [session_source.index(marker) for marker in initialize_order]
assert initialize_positions == sorted(initialize_positions), (
    "GPU renderer options must be applied after mpv_create and before mpv_initialize"
)
option_setter = session_source.split("fun setOptionString(", 1)[1].split("\n  }", 1)[0]
assert "withCore(-1, allowInitializing = true)" in option_setter, (
    "PlaybackSession must admit renderer option writes during core initialization"
)
core_gate = session_source.split("private inline fun <T> withCore(", 1)[1].split(
    "private inline fun <T> withReadyCore(", 1
)[0]
assert "_state.value.phase == PlaybackPhase.INITIALIZING" in core_gate, (
    "withCore must permit libmpv option writes in the INITIALIZING phase"
)

view_source = MPV_VIEW.read_text(encoding="utf-8")
assert "interpolationOutputFrameRate = PlaybackSession.surfaceFrameRateHintHz()" in view_source, (
    "a reattached MPVView must restore the active process-wide output rate when the core is reused"
)
init_options = view_source.split("override fun initOptions()", 1)[1].split(
    "override fun observeProperties()", 1
)[0]
timing_candidate = init_options.split("val mpvFlowFrameInterpolationCandidate =", 1)[1].split(
    "val flowInterpolationRc", 1
)[0]
assert "!frameInterpolationConfigOwned" in timing_candidate, (
    "MPV Flow timing must not override interpolation or sync options owned by mpv.conf"
)
output_rate_position = init_options.index("interpolationOutputFrameRate =")
surface_hint_position = init_options.index("updateSurfaceFrameRateHint()", output_rate_position)
assert output_rate_position < surface_hint_position, (
    "MPV Flow must reapply its output-rate hint after selecting the active interpolation target"
)
assert "PlaybackSession.setSurfaceFrameRateHintHz(interpolationOutputFrameRate)" in init_options, (
    "MPV initOptions must publish the active interpolation rate to the process-wide session"
)
surface_hint = view_source.split("internal fun updateSurfaceFrameRateHint()", 1)[1].split(
    "private val observedProps", 1
)[0]
assert "PlaybackSession.isInitialized" in surface_hint and "PlaybackSession.surfaceFrameRateHintHz()" in surface_hint, (
    "source-FPS property updates must use the initialized core's interpolation target instead of reverting the Surface hint"
)
for marker in (
    'PlaybackSession.setOptionString("display-fps-override", displayRefreshRate.hz.toString())',
    'PlaybackSession.setOptionString("interpolation", "yes")',
    'PlaybackSession.setOptionString("video-sync", "display-resample")',
    "displayRefreshRate != null",
    "flowDisplayFpsOverrideRc == 0",
    "currentDisplayRefreshRate()",
    "val mpvFlowFrameInterpolationEnabled =",
    "mpvFlowFrameInterpolationCandidate && flowTimingOptionsReady",
    'PlaybackSession.setOptionString("interpolation", "no")',
    'PlaybackSession.setOptionString("video-sync", "audio")',
    "fallback=source_passthrough",
):
    assert marker in init_options, f"MPV Flow must establish and safely gate its display cadence: missing {marker!r}"
assert init_options.index('PlaybackSession.setOptionString("interpolation", "yes")') < init_options.index(
    'PlaybackSession.setOptionString("mpvflow",'
), "the GPU frame mixer must be configured before enabling Flow synthesis"
renderer_option_order = (
    'PlaybackSession.setOptionString("gpu-api", backend.gpuApi)',
    'PlaybackSession.setOptionString("gpu-context", backend.gpuContext)',
    "PlaybackSession.setVideoOutput(backend.vo)",
)
renderer_positions = [init_options.index(marker) for marker in renderer_option_order]
assert renderer_positions == sorted(renderer_positions), (
    "MPV Flow must apply gpu-api, gpu-context, then vo during initOptions"
)
display_override_position = init_options.index(
    'PlaybackSession.setOptionString("display-fps-override", displayRefreshRate.hz.toString())'
)
vo_creation_position = init_options.index("PlaybackSession.setVideoOutput(backend.vo)")
assert display_override_position < vo_creation_position, (
    "MPV Flow must provide Android display FPS before VO creation and display-synced frame timing"
)
present_mode_position = init_options.index('PlaybackSession.setOptionString("vulkan-swap-mode", it)')
assert present_mode_position < vo_creation_position, (
    "MPV Flow must request its Vulkan present mode before gpu-next creates the Android swapchain"
)
for marker in (
    'backend.gpuApi == "vulkan"',
    'MpvConfigOverridePolicy.isOwnedByMpvConf("vulkan-swap-mode") -> null',
    'mpvFlowFrameInterpolationEnabled -> "mailbox"',
    'else -> "auto"',
    "fallback=FIFO_if_unsupported",
):
    assert marker in init_options, f"MPV Flow present-mode request must retain {marker!r}"
flow_selector = view_source.split("val flowRequiresVulkan =", 1)[1].split(
    "val gpuNextEnabled =", 1
)[0]
for marker in (
    "BuildConfig.MPV_HAS_MPVFLOW",
    "BuildConfig.MPV_SUPPORTS_VULKAN",
    "!decoderPreferences.rifeFrameInterpolation.get()",
    "!MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.FRAME_INTERPOLATION)",
    "PlaybackEngineMode.NATIVE",
):
    assert marker in flow_selector, f"Flow Vulkan selection must retain guard {marker!r}"
for marker in ('vo = "gpu-next"', 'gpuApi = "vulkan"', 'gpuContext = "androidvk"'):
    assert marker in view_source, f"MPV Flow Vulkan backend must configure {marker!r}"

flow_hwdec = init_options.split("val hardwareDecodeMode =", 1)[1].split(
    "val hardwareDecodeOptionResult", 1
)[0]
assert "RendererBackendPolicy.gpuFlowHwdecMode(" in flow_hwdec
assert "BuildConfig.MPV_SUPPORTS_MEDIACODEC_VULKAN" in flow_hwdec, (
    "MPV Flow must not request a direct MediaCodec/Vulkan path absent from the APK flavor"
)
standard_flavor = APP_BUILD.read_text(encoding="utf-8").split('create("standard") {', 1)[1].split(
    'create("noVulkan") {', 1
)[0]
assert 'MPV_SUPPORTS_MEDIACODEC_VULKAN", includeMpvFlowMpvRuntime.toString()' in standard_flavor, (
    "the standard ARM64 Flow build must advertise direct import only when the custom runtime was packaged"
)
app_build_source = APP_BUILD.read_text(encoding="utf-8")
assert 'val includeMpvFlowMpvRuntime = requestedMpvFlowMpvRuntime && activeAbis == listOf("arm64-v8a")' in app_build_source, (
    "the custom Vulkan mapper capability must be limited to the native arm64 runtime build"
)
no_vulkan_flavor = app_build_source.split('create("noVulkan") {', 1)[1].split(
    'create("fongmi") {', 1
)[0]
assert 'MPV_SUPPORTS_MEDIACODEC_VULKAN", "false"' in no_vulkan_flavor, (
    "the no-Vulkan flavor must never advertise direct MediaCodec/Vulkan import"
)
mpv_conf_source = MPV_CONF_POLICY.read_text(encoding="utf-8")
renderer_ownership = mpv_conf_source.split("RENDERER(", 1)[1].split("\n  ),", 1)[0]
assert '"vulkan-swap-mode"' in renderer_ownership, (
    "explicit mpv.conf Vulkan swap-mode configuration must be discoverable and preserved"
)
decoder_screen = DECODER_SCREEN.read_text(encoding="utf-8")
for marker in (
    "val mpvFlowForcesRenderer =",
    "value = gpuNext || mpvFlowForcesRenderer",
    "value = (useVulkan || mpvFlowForcesRenderer) && isVulkanSupported",
    "enabled = !mpvFlowForcesRenderer",
    "R.string.pref_decoder_mpvflow_compute_api_title",
):
    assert marker in decoder_screen, f"the Decoder screen must truthfully distinguish Flow rendering and compute: missing {marker!r}"
compute_title_position = decoder_screen.index("R.string.pref_decoder_mpvflow_compute_api_title")
compute_selector_start = decoder_screen.rfind("ListPreference(", 0, compute_title_position)
compute_selector_end = decoder_screen.index("PreferenceDivider()", compute_selector_start)
compute_selector = decoder_screen[compute_selector_start:compute_selector_end]
for marker in (
    "ListPreference(",
    "onValueChange = preferences.mpvFlowComputeApi::set",
    "values = MpvFlowComputeApi.entries",
    "!mpvFlowComputeApiConfigOwned",
    "MpvFlowComputeApi.OPENCL",
    "MpvFlowComputeApi.VULKAN",
):
    assert marker in compute_selector, f"the compute selector must persist real backend choices: missing {marker!r}"
resource_source = STRING_RESOURCES.read_text(encoding="utf-8")
for marker in (
    "Auto uses native Vulkan/SPIR-V compute to avoid per-frame Vulkan/OpenCL ownership transfers",
    "cl_qcom_android_ahardwarebuffer_host_ptr",
    "No Vulkan compute fallback is used for this explicit choice",
    "Force Vulkan/SPIR-V compute",
):
    assert marker in resource_source, f"compute API choice must have accurate user-facing semantics: missing {marker!r}"
assert "Decoder input may use MediaCodec direct mode or mediacodec-copy" in resource_source, (
    "Flow settings must distinguish decoder-input copy mode from interpolation compute"
)
assert "synthesized output stays GPU-side with no CPU pixel readback" in resource_source, (
    "Flow settings must state the actual synthesized-output path"
)

flow_gpu_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c").read_text(encoding="utf-8")
opencl_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_opencl.c").read_text(encoding="utf-8")
opencl_header = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_opencl.h").read_text(encoding="utf-8")
bridge_source = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_opencl_vk_bridge.c").read_text(encoding="utf-8")
bridge_header = (ROOT / "app/src/main/cpp/mpvflow/mpvflow_opencl_vk_bridge.h").read_text(encoding="utf-8")
media3_jni_source = (ROOT / "app/src/main/cpp/media3flow_vulkan_jni.cpp").read_text(encoding="utf-8")
assert 'config.diagnostic_engine = "media3";' in media3_jni_source
assert "qcom2_adapter_ready=%d qcom2_luma_pass_ready=%d" in media3_jni_source
cmake_source = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
manifest_source = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for marker in (
    "#define MPVFLOW_GRID_STEP 4",
    "int radius = has ? 2 : c->search_radius;",
    "parent_residual=q.z; parent_uniqueness=q.w",
    "if(has_coarse&&parent_residual>0.12f&&parent_uniqueness>0.0005f)search_radius=min(radius*2,16)",
    "inline float subpixel_offset",
    "sample_coarse(coarse,cgw,cgh",
    "flow_at_edge_aware(forward,backward",
    "ring<=search_radius&&!stop",
    "flow_at_bilinear(forward,backward",
    "inline float reliability(float4 flow,float cycle,float valid)",
    "cycle_error0",
    "boundary_conflict",
    "int block_size = level == MPVFLOW_LEVELS - 1 ? 4 : 8;",
    "(float)best/((float)(block_size*block_size)*255.0f),uniqueness",
    "aw-4,ah-4",
    "SET_ARG(c->synth_kernel, 10, luma0)",
    "SET_ARG(c->synth_kernel, 15, dstride)",
):
    assert marker in opencl_source, (
        f"OpenCL motion and artifact safeguards must match Vulkan Flow: missing {marker!r}"
    )
for marker in (
    "mpvflow_opencl_create(",
    "mpvflow_opencl_vk_bridge_create(",
    "effective_api=opencl",
    "flow_opencl_analyze_pair(",
    "flow_opencl_synthesize(",
    "ctx->requested_compute_api != MPVFLOW_GPU_COMPUTE_AUTO",
):
    assert marker in flow_gpu_source, f"MPVFlow must keep OpenCL explicit and reject its failures without silently switching backends: missing {marker!r}"
assert 'source_format == VK_FORMAT_R8G8B8A8_UNORM' in flow_gpu_source and \
       'const size_t local = 64' in opencl_source, \
       "OpenCL must skip redundant RGBA8 preprocessing and use a fixed synthesis workgroup"
assert 'auto_prefers_vulkan_native_pipeline_opencl_optional' in flow_gpu_source and \
       'if (requested_api == MPVFLOW_GPU_COMPUTE_OPENCL)' in flow_gpu_source, \
       "AUTO must use the native Vulkan path while preserving explicit OpenCL"
assert 'cl_qcom_android_ahardwarebuffer_host_ptr' in opencl_source, (
    "OpenCL must import shared Android HardwareBuffer storage rather than exposing a disconnected compute toggle"
)
opencl_buffer_creation = opencl_source.split("static enum MPVFlowOpenCLStatus make_buffer(", 1)[1].split(
    "enum MPVFlowOpenCLStatus mpvflow_opencl_import_ahardwarebuffer(", 1
)[0]
assert "flags = MPVFLOW_OPENCL_QCOM_IMPORT_FLAGS" in opencl_buffer_creation and \
       "MPVFLOW_OPENCL_QCOM_IMPORT_SIZE_ARG" in opencl_buffer_creation and \
       "MPVFLOW_OPENCL_QCOM_USE_HOST_PTR_FLAG ((uint64_t)0x00000008u)" in opencl_header and \
       "MPVFLOW_OPENCL_QCOM_EXT_HOST_PTR_FLAG ((uint64_t)0x20000000u)" in opencl_header and \
       "(MPVFLOW_OPENCL_QCOM_USE_HOST_PTR_FLAG | MPVFLOW_OPENCL_QCOM_EXT_HOST_PTR_FLAG)" in opencl_header and \
       "MPVFLOW_OPENCL_QCOM_AHB_ALLOCATION_TYPE 0x4119u" in opencl_header and \
       "MPVFLOW_OPENCL_QCOM_HOST_WRITEBACK_POLICY 0x40A5u" in opencl_header, (
    "QCOM AHardwareBuffer imports must match the public Adreno host_ptr call shape: size zero and no explicit access qualifier"
)
assert "desc_in->allocation_bytes, &desc, &err" in opencl_buffer_creation, (
    "CL_INVALID_OPERATION must retry the QCOM import with the validated BLOB allocation size"
)
assert "MPVFLOW_OPENCL_CL_INVALID_OPERATION" in opencl_buffer_creation, (
    "the legacy size-zero fallback must be limited to the known QCOM CL_INVALID_OPERATION failure"
)
assert "*cl_create_error_out = err" in opencl_buffer_creation, (
    "the raw vendor clCreateBuffer error must be preserved for device-side diagnosis"
)
assert "c->clCreateBuffer(c->context, flags,\n                               MPVFLOW_OPENCL_QCOM_IMPORT_SIZE_ARG,\n                               &desc, &err)" in opencl_buffer_creation, (
    "the QCOM AHardwareBuffer primary import must use the private-extension size-zero form"
)
assert "c->clCreateBuffer(c->context, flags,\n                                   desc_in->allocation_bytes, &desc, &err)" in opencl_buffer_creation, (
    "the validated allocation-size form must remain only as a fallback"
)
assert "MPVFLOW_OPENCL_QCOM_AHB_ALLOCATION_TYPE 0x4119u" in opencl_header and \
       "0x40C7 is CL_CONTEXT_PROTECTED_QCOM" in opencl_header, \
       "the AHardwareBuffer allocation token must not alias the protected-context token"
assert "MPVFLOW_OPENCL_QCOM_IMPORT_SIZE_ARG ((size_t)0)" in opencl_header
assert "int32_t mpvflow_opencl_buffer_release" in opencl_header and \
       "err = context->clReleaseMemObject(buffer->mem)" in opencl_source, (
    "diagnostic cleanup must retain the raw clReleaseMemObject result"
)

assert "qcom_primary_size_arg=%zu" in bridge_source and \
       "qcom_size_arg=%zu" not in bridge_source, \
       "backend errors must report the actual primary size argument, not allocation bytes"

for extension in (
    "cl_qcom_ext_host_ptr", "cl_qcom_ext_host_ptr_iocoherent",
    "cl_qcom_android_ahardwarebuffer_host_ptr", "cl_qcom_android_native_buffer_host_ptr",
    "cl_qcom_dmabuf_host_ptr", "cl_qcom_ahardwarebuffer_direct_import",
    "cl_arm_import_memory_android_hardware_buffer", "cl_khr_external_memory_android_hardware_buffer",
    "cl_khr_external_memory",
    "cl_khr_external_memory_dma_buf",
    "cl_khr_external_memory_opaque_fd", "cl_khr_external_semaphore",
    "cl_khr_external_semaphore_sync_fd", "cl_khr_egl_event", "cl_khr_egl_image",
    "cl_khr_gl_sharing", "cl_ext_image_from_buffer",
    "cl_ext_image_requirements_info", "cl_khr_byte_addressable_store",
):
    assert f'"{extension}"' in opencl_source, (
        f"compact OpenCL extension diagnostics must include a presence boolean for {extension}"
    )
assert "mpvflow_opencl_get_extension_presence_info" in opencl_header and \
       "cl_qcom_ahardwarebuffer_direct_import" in opencl_source and \
       "event=opencl_extension_presence %s" in flow_gpu_source and \
       'extensions=\\"%s\\"' not in opencl_source, (
    "extension-presence booleans must be emitted separately instead of appending the full long list"
)

before_bind_probe = bridge_source.split(
    "static void probe_import_before_vk_bind(", 1
)[1].split("static enum MPVFlowOpenCLVkBridgeStatus buffer_create_internal(", 1)[0]
for marker in (
    "AHardwareBuffer_allocate(&requested, &ahb)",
    "b->get_ahb_properties(",
    "mpvflow_opencl_import_ahardwarebuffer(",
    "mpvflow_opencl_buffer_release(b->opencl, opencl_buffer)",
    "AHardwareBuffer_release(ahb)",
):
    assert marker in before_bind_probe, f"pre-bind disposable probe is missing {marker}"
assert "vkCreateBuffer(" not in before_bind_probe and "vkBindBufferMemory(" not in before_bind_probe, (
    "the pre-bind import attempt must occur before any Vulkan buffer bind"
)
post_bind_probe = bridge_source.split(
    "static enum MPVFlowOpenCLVkBridgeStatus buffer_create_internal(", 1
)[1].split("enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_create(", 1)[0]
assert post_bind_probe.index("vkBindBufferMemory(b->vk->device") < post_bind_probe.index(
    "mpvflow_opencl_import_ahardwarebuffer("
), "the after-bind import must run only after vkBindBufferMemory succeeds"
for field in (
    "requested_width_bytes", "actual_format", "actual_width", "actual_height",
    "actual_stride", "actual_layers", "actual_usage", "vk_ahb_allocation_size",
    "vk_ahb_memory_type_bits", "vk_external_memory_features",
    "vk_buffer_requirement_size", "vk_buffer_requirement_alignment",
    "vk_buffer_requirement_memory_type_bits", "vk_allocate_memory_result",
    "vk_bind_buffer_memory_result", "cl_create_buffer_error",
    "cl_release_mem_object_error", "vk_buffer_destroyed", "vk_memory_freed",
    "ahb_released",
):
    assert field in bridge_header, f"import probe diagnostics must expose {field}"
diagnostic_probe = bridge_source.split(
    "void mpvflow_opencl_vk_bridge_probe_import_stages(", 1
)[1].split("enum MPVFlowOpenCLVkBridgeStatus mpvflow_opencl_vk_buffer_destroy(", 1)[0]
assert "MPVFLOW_IMPORT_DIAGNOSTIC_DIMENSION 32u" in bridge_source and \
       "MPVFLOW_IMPORT_DIAGNOSTIC_DIMENSION,\n        MPVFLOW_IMPORT_DIAGNOSTIC_DIMENSION" in diagnostic_probe and \
       "mpvflow_opencl_buffer_release(b->opencl, buffer->opencl_buffer)" in diagnostic_probe and \
       diagnostic_probe.index("mpvflow_opencl_buffer_release") < diagnostic_probe.index(
           "mpvflow_opencl_vk_buffer_destroy(buffer)"
       ), "diagnostic imports must use a small fresh buffer and release OpenCL before Vulkan/AHB cleanup"
assert "mpvflow_opencl_analyze_pair" not in diagnostic_probe and \
       "mpvflow_opencl_synthesize" not in diagnostic_probe, (
    "diagnostic-only imports must not run kernels or be treated as proof of backend readiness"
)
assert flow_gpu_source.index("mpvflow_opencl_vk_bridge_probe_import_stages(") < flow_gpu_source.index(
    "probe_status = flow_opencl_interop_probe(ctx)"
) and "probe_status == MPVFLOW_OPENCL_VK_BRIDGE_OK" in flow_gpu_source, (
    "only the complete kernel/Vulkan round-trip may enable OpenCL; import diagnostics stay observational"
)
assert "cl_error_available=%d" in bridge_source and \
       "qcom_primary_size_arg=%zu qcom_access_flags=default" in bridge_source and \
       "qcom_required_padding_bytes=%zu" in bridge_source and \
       "ahb_allocation_bytes=%zu desc=" in bridge_source, (
    "OpenCL import failures must distinguish the QCOM call arguments from the validated AHardwareBuffer descriptor"
)
assert "CL_DEVICE_EXT_MEM_PADDING_IN_BYTES_QCOM 0x40A0u" in opencl_source and \
       "CL_DEVICE_EXT_MEM_PADDING_IN_BYTES_QCOM" in opencl_source and \
       "qcom_ext_mem_padding_bytes=%zu" in opencl_source and \
       "size_t qcom_ext_mem_padding_bytes" in opencl_source and \
       "size_t qcom_page_size_bytes" in opencl_source, (
    "OpenCL must query and report QCOM padding/page-size values using the extension's size_t query shape"
)
assert "mpvflow_opencl_get_qcom_ext_mem_padding_bytes" in bridge_source and \
       "size_t qcom_padding_bytes" in bridge_source and \
       "ahb_blob_bytes = logical_bytes + (VkDeviceSize)qcom_padding_bytes" in bridge_source and \
       "ahb_desc.width = (uint32_t)ahb_blob_bytes" in bridge_source, (
    "the shared BLOB AHardwareBuffer allocation must include the device-required QCOM tail padding"
)
for marker in (
    "clGetPlatformInfo",
    "CL_PLATFORM_VERSION",
    "CL_DEVICE_VERSION",
    "CL_DRIVER_VERSION",
    "CL_DEVICE_OPENCL_C_VERSION",
    "device_extensions",
    "library_path",
    "mpvflow_opencl_get_runtime_info",
    "event=opencl_device",
):
    assert marker in opencl_source or marker in flow_gpu_source, (
        f"OpenCL platform/device/driver/extension identity must be logged: missing {marker!r}"
    )
opencl_synthesis = flow_gpu_source.split("static enum MPVFlowGPUResult flow_opencl_synthesize(", 1)[1].split(
    "enum MPVFlowGPUResult mpvflow_gpu_synthesize(", 1
)[0]
new_output_buffer = opencl_synthesis.split("if (!pair->opencl_output) {", 1)[1].split(
    "for (int i = 0; i < 2; i++)", 1
)[0]
assert "mpvflow_opencl_vk_buffer_create" in new_output_buffer and \
       "mpvflow_opencl_vk_buffer_opencl_finished(pair->opencl_output)" in new_output_buffer, (
    "a newly created OpenCL output buffer must be marked foreign-ready before its first OpenCL access"
)
assert 'cl_qcom_ext_host_ptr' in opencl_source and 'has_extension(exts, MPVFLOW_BASE_EXTENSION)' in opencl_source, (
    "OpenCL must verify the Qualcomm base host-pointer extension required by its AHardwareBuffer import ABI"
)
assert "vkCmdCopyImageToBuffer" in bridge_source and "vkCmdCopyBufferToImage" in bridge_source, (
    "the OpenCL interop path must transfer textures using synchronized GPU-only Vulkan copies"
)
assert "vkCmdFillBuffer" in bridge_source and "mpvflow_opencl_vk_fill_buffer" in bridge_source, (
    "the OpenCL startup smoke test must exercise Vulkan writes into shared storage without CPU uploads"
)
interop_probe = flow_gpu_source.split("static enum MPVFlowOpenCLVkBridgeStatus flow_opencl_interop_probe(", 1)[1].split(
    "MPVFlowGPUContext *mpvflow_gpu_create(", 1
)[0]
for marker in (
    "mpvflow_opencl_analyze_pair",
    "mpvflow_opencl_synthesize",
    "mpvflow_opencl_vk_copy_buffer_to_texture",
    "probe_dimension = 64",
):
    assert marker in interop_probe, (
        f"OpenCL initialization must execute an analyze/synthesize Vulkan round-trip: missing {marker!r}"
    )
assert "probe=kernel_interop_roundtrip" in flow_gpu_source
assert "opencl_status_valid=%d" in interop_probe, (
    "an OpenCL status must not be reported as a returned error when buffer import failed before OpenCL execution"
)
opencl_analyze_wrapper = flow_gpu_source.split("static enum MPVFlowGPUResult flow_opencl_analyze_pair(", 1)[1].split(
    "enum MPVFlowGPUResult mpvflow_gpu_analyze_pair(", 1
)[0]
assert opencl_analyze_wrapper.count("mpvflow_opencl_finish(ctx->opencl)") == 1, (
    "analysis must retain its failure drain but avoid a duplicate finish after successful internal completion"
)
opencl_synthesize_wrapper = flow_gpu_source.split("static enum MPVFlowGPUResult flow_opencl_synthesize(", 1)[1].split(
    "enum MPVFlowGPUResult mpvflow_gpu_synthesize(", 1
)[0]
assert "if (mpvflow_opencl_finish(ctx->opencl) == MPVFLOW_OPENCL_OK)" in opencl_synthesize_wrapper, (
    "failed OpenCL synthesis must still finish before returning shared buffers to Vulkan"
)
assert opencl_synthesize_wrapper.count("mpvflow_opencl_finish(ctx->opencl)") == 2, (
    "synthesis must retain only its geometry/error cleanup finishes and avoid a duplicate success-path finish"
)
pair_cleanup = flow_gpu_source.split("static void destroy_pair_textures(", 1)[1].split("\n}\n", 1)[0]
assert "} else {\n                        *owned[i] = NULL;\n                    }" in pair_cleanup and \
       "\n                *owned[i] = NULL;\n" not in pair_cleanup, (
    "failed OpenCL buffer destruction must retain its handle so runtime fallback cannot mistake live interop resources for cleaned resources"
)
failed_synthesis_context = vo_patch.split(
    'bypass_reason = output ? "synthesis_failed" : "synthesis_output_missing";', 1
)[1].splitlines()[:5]
assert any("flow_release_pair(p);" in line for line in failed_synthesis_context), (
    "a failed synthesis may tear down pair-owned resources; the MPV frame-ID cache must not reuse that pair"
)
for marker in (
    "flow_init_retry_count < 2",
    '"opencl_initialization"',
    "flow_init_retry_after_ns",
    "retry_scheduled=%d",
):
    assert marker in vo_patch, f"explicit OpenCL initialization should use a bounded retry: missing {marker!r}"
media3_sink_source = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVideoSink.kt").read_text(
    encoding="utf-8"
)
media3_native_source = (
    ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/Media3FlowVulkanNative.kt"
).read_text(encoding="utf-8")
assert "nativeGetComputeApiName" in media3_jni_source and "nativeGetComputeApiName" in media3_native_source
assert "effective_compute_api=${effectiveComputeApiName()}" in media3_sink_source
assert "backend=vulkan-spirv" not in media3_sink_source, (
    "Media3 Flow diagnostics must not claim Vulkan compute when OpenCL was requested or selected"
)
for source in ("mpvflow/mpvflow_opencl.c", "mpvflow/mpvflow_opencl_vk_bridge.c"):
    assert source in cmake_source, f"Native Media3 must compile {source}"
assert "dl" in cmake_source and 'android:name="libOpenCL.so"' in manifest_source and 'android:required="false"' in manifest_source, (
    "OpenCL must be dynamically loaded as an optional Android vendor library"
)
assert "flow_compute_api=%s" in media3_jni_source and "renderer_api=vulkan flow_compute_api=%s" in media3_jni_source, (
    "Native Media3 diagnostics must distinguish Vulkan rendering from the effective Flow compute backend"
)
for marker in (
    "VK_NV_OPTICAL_FLOW_EXTENSION_NAME",
    "VkPhysicalDeviceOpticalFlowFeaturesNV",
    "VK_QUEUE_OPTICAL_FLOW_BIT_NV",
    "params.extra_queues = VK_QUEUE_OPTICAL_FLOW_BIT_NV",
    "params.features = &optionalFeatures",
    "VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME",
    "VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME",
    "VkPhysicalDeviceImageProcessingFeaturesQCOM",
    "VkPhysicalDeviceImageProcessing2FeaturesQCOM",
    "textureBlockMatch = VK_TRUE",
    "textureBlockMatch2 = VK_TRUE",
    "optionalFeatures.pNext = optionalFeatureChain",
):
    assert marker in media3_jni_source, f"Media3 should opportunistically request optical-flow capability: missing {marker!r}"
for marker in (
    "event=optical_flow_capability",
    "vkGetPhysicalDeviceOpticalFlowImageFormatsNV",
    "VK_OPTICAL_FLOW_USAGE_INPUT_BIT_NV",
    "VK_OPTICAL_FLOW_USAGE_OUTPUT_BIT_NV",
    "session_execution=optional_adapter_initialization_follows",
):
    assert marker in flow_gpu_source, f"shared core must report verified optical-flow capability without overclaiming execution: missing {marker!r}"
for marker in (
    "event=qcom_block_match_capability",
    "VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME",
    "VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME",
    "VkPhysicalDeviceImageProcessingFeaturesQCOM",
    "VkPhysicalDeviceImageProcessing2FeaturesQCOM",
    "texture_block_match_supported=%d",
    "texture_block_match2_supported=%d",
    "base_extension_enabled=%d",
    "window_extension_enabled=%d",
    "maxBlockMatchRegion",
    "maxBlockMatchWindow",
    "VK_FORMAT_FEATURE_2_BLOCK_MATCHING_BIT_QCOM",
    "VK_IMAGE_USAGE_SAMPLE_BLOCK_MATCH_BIT_QCOM",
    "vkGetPhysicalDeviceImageFormatProperties2",
    "window_extension_not_supported",
    "block_match && window_match && properties_queried",
    "shader_execution=optional_qcom2_custom_pass_initialization_follows",
    "event=qcom_seed_backend engine=%s state=%s",
    "optional_luma_shader_compile_failed",
    "MPVFlowGPUQcomDiagnostics",
    "qcom_pair_attempts++",
    "qcom_dispatches++",
    "qcom_fallbacks++",
    "mpvflow_gpu_get_qcom_diagnostics",
):
    assert marker in flow_gpu_source, f"shared core must query QCOM capability before optional adapter setup: missing {marker!r}"

qcom_adapter_source = (
    ROOT / "app/src/main/cpp/mpvflow/mpvflow_vulkan_qcom.c"
).read_text(encoding="utf-8")
for marker in (
    "#if defined(VK_QCOM_image_processing) && defined(VK_QCOM_image_processing2)",
    "VK_DESCRIPTOR_TYPE_BLOCK_MATCH_IMAGE_QCOM",
    "VK_IMAGE_USAGE_SAMPLE_BLOCK_MATCH_BIT_QCOM",
    "VK_IMAGE_USAGE_TRANSFER_SRC_BIT",
    "VK_IMAGE_USAGE_TRANSFER_DST_BIT",
    "pl_vulkan_hold_ex",
    "vkCmdCopyImage",
    "vkCmdDispatch",
    "refinement=full_baseline_sad_search",
):
    assert marker in qcom_adapter_source, f"QCOM adapter must stage, dispatch, and preserve baseline refinement: missing {marker!r}"
for marker in (
    "mpvflow_vulkan_qcom_ensure(ctx->qcom",
    "mpvflow_vulkan_qcom_dispatch(ctx->qcom)",
    "event=qcom_block_match_dispatch state=fallback",
    "fallback=full_baseline_sad_search",
    "const bool qcom_parent = qcom_dispatched",
    "(qcom_parent ? 4 : 0)",
    "mpvflow_vulkan_qcom_seed(ctx->qcom, dir)",
):
    assert marker in flow_gpu_source, f"shared flow core must route QCOM seeds to SAD refinement with fallback: missing {marker!r}"

nv_selftest_source = (
    ROOT / "app/src/main/cpp/mpvflow/mpvflow_vulkan_optical_selftest.c"
).read_text(encoding="utf-8")
nv_optical_source = (
    ROOT / "app/src/main/cpp/mpvflow/mpvflow_vulkan_optical.c"
).read_text(encoding="utf-8")
nv_optical_header_source = (
    ROOT / "app/src/main/cpp/mpvflow/mpvflow_vulkan_optical.h"
).read_text(encoding="utf-8")
advanced_preferences_source = (
    ROOT / "app/src/main/java/app/infinity/mpvz/ui/preferences/AdvancedPreferencesScreen.kt"
).read_text(encoding="utf-8")
for marker in (
    "#if defined(MPVFLOW_ENABLE_NV_SELF_TEST) && defined(VK_NV_optical_flow)",
    "VK_FORMAT_R16G16B16A16_SFLOAT",
    "VK_IMAGE_USAGE_TRANSFER_SRC_BIT",
    "VK_IMAGE_USAGE_SAMPLED_BIT",
    "VK_IMAGE_USAGE_TRANSFER_DST_BIT",
    "VK_IMAGE_USAGE_STORAGE_BIT",
    "VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT",
    "VK_FORMAT_FEATURE_2_OPTICAL_FLOW_IMAGE_BIT_NV",
    "VK_FORMAT_FEATURE_2_OPTICAL_FLOW_VECTOR_BIT_NV",
    "event=alternate_usage state=queried role=input",
    "event=alternate_usage state=queried role=output",
    "event=dispatch state=not_attempted reason=required_nv_optical_format_feature_missing",
    "vkCmdClearColorImage",
    "VK_PIPELINE_STAGE_ALL_COMMANDS_BIT",
    "vkCmdCopyImageToBuffer",
    "vkWaitForFences",
    "self_test_half_to_float",
    "event=grid_cell",
    "half_bits=(%04x,%04x,%04x,%04x)",
    "production_rgba16f_decoder_enabled=0",
):
    assert marker in nv_selftest_source, f"manual NV self-test is missing {marker!r}"
assert "NV_SELF_TEST_INPUT_FORMAT, storage_usage" in nv_selftest_source
assert "NV_SELF_TEST_INPUT_FORMAT, color_attachment_usage" in nv_selftest_source
assert "NV_SELF_TEST_VECTOR_FORMAT, storage_usage" in nv_selftest_source
assert "normal_playback_adapter_unchanged=1" in nv_selftest_source
feature_gate = nv_selftest_source.index(
    "if (!input_feature_query || !vector_feature_query ||"
)
image_allocation = nv_selftest_source.index(
    "self_test_create_image(&test, &test.input[0]"
)
assert feature_gate < image_allocation, (
    "NV input/vector format feature failures must stop the probe before image allocation"
)
assert "mpvflow/mpvflow_vulkan_optical_selftest.c" in cmake_source
assert '"$<$<CONFIG:Debug>:MPVFLOW_ENABLE_NV_SELF_TEST=1>"' in cmake_source
assert '#ifdef __cplusplus\nextern "C" {' in nv_optical_header_source
assert "nativeRunNvOpticalFlowSelfTest" in media3_jni_source
assert "BuildConfig.DEBUG && BuildConfig.MPV_SUPPORTS_VULKAN" in advanced_preferences_source
assert "nativeRunNvOpticalFlowSelfTest(testContext)" in advanced_preferences_source
assert "nativeDestroyContext(testContext)" in advanced_preferences_source
assert "nativeRunNvOpticalFlowSelfTest" not in media3_sink_source, (
    "the manual NV diagnostic must not run during normal Media3 playback"
)
assert "mpvflow_vulkan_optical_debug_self_test" not in flow_gpu_source, (
    "the shared playback Flow core must not invoke the manual NV diagnostic"
)
production_vector_candidates = nv_optical_source.split(
    "static const struct Candidate candidates[]", 1
)[1].split("};", 1)[0]
assert "VK_FORMAT_R16G16B16A16_SFLOAT" not in production_vector_candidates, (
    "the debug probe must not add RGBA16F to the production adapter's documented S10.5 decoder candidates"
)

for marker in (
    "params.async_compute = false;",
    "params.async_transfer = false;",
    "VK_SHARING_MODE_EXCLUSIVE",
    "event=queue_capability",
    "dedicated_compute_families=%u",
    "async_queue_disabled_reason=exclusive_ahb_images_require_verified_queue_ownership",
    "logQueueFamilyCapability(vulkan);",
):
    assert marker in media3_jni_source, f"Media3 must inventory available queues but preserve exclusive-image safety: missing {marker!r}"
assert "VK_QCOM_IMAGE_PROCESSING_EXTENSION_NAME" in media3_jni_source
assert "VK_QCOM_IMAGE_PROCESSING_2_EXTENSION_NAME" in media3_jni_source
assert "imageProcessingFeature.textureBlockMatch = VK_TRUE" in media3_jni_source
assert "imageProcessing2Feature.textureBlockMatch2 = VK_TRUE" in media3_jni_source
assert "AHardwareBuffer_lock" not in bridge_source and "clEnqueueReadBuffer" not in opencl_source, (
    "Flow must not add CPU pixel readback to the OpenCL backend"
)
assert "vk->get_proc_addr" in bridge_source
assert '"vkGetPhysicalDeviceExternalBufferPropertiesKHR"' in bridge_source
assert "vkGetPhysicalDeviceExternalBufferProperties(vk->phys_device" not in bridge_source, (
    "Android's Vulkan loader may not export this core entry point for direct linking"
)
frame_interpolation_ownership = mpv_conf_source.split("val FRAME_INTERPOLATION =", 1)[1].split(
    "\n    )", 1
)[0]
for option in ("gpu-api", "gpu-context", "interpolation", "video-sync", "display-fps-override"):
    assert f'"{option}"' in frame_interpolation_ownership, (
        f"mpv.conf-owned {option} must disable MPV Flow rather than override the configured path"
    )
assert '"vulkan-swap-mode"' not in frame_interpolation_ownership, (
    "an explicit Vulkan swap mode must be preserved without disabling MPV Flow"
)
policy_source = POLICY.read_text(encoding="utf-8")
flow_hwdec_policy = policy_source.split("fun gpuFlowHwdecMode(", 1)[1].split(
    "fun preferredHwdecMode(", 1
)[0]
for marker in (
    "buildSupportsMediaCodecVulkan: Boolean",
    "usesVulkan = true",
    "buildSupportsMediaCodecVulkan = buildSupportsMediaCodecVulkan",
    "): String = preferredHwdecMode(",
):
    assert marker in flow_hwdec_policy, f"Flow hwdec policy must honor its Vulkan capability: missing {marker!r}"
policy_tests = POLICY_TEST.read_text(encoding="utf-8")
for marker in (
    '"mediacodec,mediacodec-copy,no"',
    '"mediacodec-copy,no"',
    '"no"',
    "buildSupportsMediaCodecVulkan = true",
    "buildSupportsMediaCodecVulkan = false",
):
    assert marker in policy_tests, f"Flow hwdec tests must cover direct, copy and disabled modes: missing {marker!r}"

start_marker = "<<'PY'\n"
start = build.index(start_marker) + len(start_marker)
end = build.index("\nPY\n", start)
builder_patcher = build[start:end]
with tempfile.TemporaryDirectory(prefix="mpvflow-builder-patch-test-") as temporary:
    fixture = Path(temporary)
    ci = fixture / "ci.sh"
    depinfo = fixture / "depinfo.sh"
    downloads = fixture / "download-deps.sh"
    libplacebo = fixture / "libplacebo.sh"
    android_mk = fixture / "Android.mk"
    mpv = fixture / "mpv.sh"
    ci.write_text(
        'msg "Fetching mpv"\n'
        "mkdir -p deps/mpv\n"
        "$WGET https://github.com/mpv-player/mpv/archive/master.tar.gz -O master.tgz\n"
        "tar -xzf master.tgz -C deps/mpv --strip-components=1\n"
        "rm master.tgz\n"
        "build_prefix() {\n"
        "IN_CI=1 ./include/download-deps.sh\n"
        'msg "Compiling"\n'
        "./buildall.sh --only-deps mpv\n"
        "}\n"
        "./buildall.sh -n mpv\n"
        "./buildall.sh -n\n",
        encoding="utf-8",
    )
    depinfo.write_text("dep_mpv=(ffmpeg libass lua libplacebo qairt)\nv_ci_ffmpeg=n8.0.1\n", encoding="utf-8")
    downloads.write_text(
        "[ ! -d libplacebo ] && git clone --recursive https://github.com/haasn/libplacebo\n# mpv\n",
        encoding="utf-8",
    )
    libplacebo.write_text(
        'unset CC CXX\n'
        'meson setup $build --cross-file "$prefix_dir"/crossfile.txt \\\n'
        '\t-Dvulkan=enabled -Ddemos=false\n',
        encoding="utf-8",
    )
    android_mk.write_text(
        "include $(CLEAR_VARS)\n"
        "LOCAL_MODULE := libmpv\n"
        "LOCAL_SRC_FILES := $(PREFIX)/lib/libmpv.so\n"
        "LOCAL_EXPORT_C_INCLUDES := $(PREFIX)/include\n"
        "include $(PREBUILT_SHARED_LIBRARY)\n"
        "LOCAL_SHARED_LIBRARIES := swscale avcodec mpv\n",
        encoding="utf-8",
    )
    mpv.write_text(
        'unset CC CXX # meson wants these unset\n'
        'meson setup $build --cross-file "$prefix_dir"/crossfile.txt \\\n'
        '\t--default-library shared \\\n'
        '\t-Diconv=disabled -Dlua=enabled \\\n'
        '\t-Dlibmpv=true -Dcplayer=false \\\n'
        '\t-Dmanpage-build=disabled\n',
        encoding="utf-8",
    )
    result = subprocess.run(
        [sys.executable, "-", str(ci), str(depinfo), str(downloads), str(libplacebo), str(android_mk), str(mpv)],
        input=builder_patcher,
        check=False,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, f"builder patcher fixture failed:\n{result.stdout}{result.stderr}"
    patched_downloads = downloads.read_text(encoding="utf-8")
    assert "0d043c7f6f79cd3687c023454bdacbe615e4d96f" in patched_downloads, "libplacebo pin was lost"
    assert "git -C glslang fetch --depth 1 origin 1062752a891c95b2bfeed9e356562d88f9df84ac" in patched_downloads
    patched_ci = ci.read_text(encoding="utf-8")
    early_prepare = 'python3 "$MPVFLOW_ROOT/scripts/prepare-mpvflow.py" --mpv-dir deps/mpv --libplacebo-dir deps/libplacebo'
    assert early_prepare in patched_ci, "Flow preparation must be injected after source download"
    assert patched_ci.index(early_prepare) < patched_ci.index('msg "Compiling"') < patched_ci.index(
        "./buildall.sh --arch arm64 --only-deps mpv"
    ), "Flow Vulkan/glslang source fixes must run before the dependency prefix builds libplacebo"
    patched_placebo = libplacebo.read_text(encoding="utf-8")
    assert "-Dglslang=enabled" in patched_placebo and "-Dvulkan=enabled" in patched_placebo
    assert "cmake --install" in patched_placebo and "libSPIRV.a" in patched_placebo
    patched_mpv = mpv.read_text(encoding="utf-8")
    assert "\t-Dmanpage-build=disabled -Dvulkan=enabled\n" in patched_mpv, (
        "MPV Vulkan must be enabled in the pinned Meson options block"
    )
    assert "--arch arm64" in ci.read_text(encoding="utf-8"), "native build must remain arm64-only"

flow_vo_patch = flow_vo_patch_source
assert "flow_prepare_frame(vo, &mix, &flow_prepared, frame->still," in flow_vo_patch
assert "if (!allow_synthesis)" in flow_vo_patch
assert "should_draw && pl_swapchain_start_frame(p->sw, &swframe)" in flow_vo_patch
assert "should_draw && !flow_synthesis_rate_limited && pl_swapchain_start_frame" not in flow_vo_patch
assert "flow_pending_rate_limiter_eligible && p->flow_pending_synthesized" in flow_vo_patch
assert "high_precision_rgba16f_synthesis_unavailable" in flow_vo_patch
assert "analysis=bt709_bt1886_tonemapped" in flow_vo_patch
assert "frame->repr.bits.color_depth > 8" in flow_vo_patch
assert "source_color.transfer = PL_COLOR_TRC_LINEAR" in flow_vo_patch
assert "flow_color.transfer = PL_COLOR_TRC_LINEAR" in flow_vo_patch

rate_limit_test = r"""
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include "mpvflow_rate_limit.h"

int main(void)
{
    struct mpvflow_rate_limiter limiter = {0};
    uint64_t submits = 0;
    uint64_t last_submit_ns = 0;
    bool has_last_submit = false;

    /* 6-ms callbacks oversupply 120 fps; every accepted frame must still be spaced safely. */
    for (uint64_t now = 0; now < 10ULL * MPVFLOW_RATE_LIMIT_ONE; now += 6000000ULL) {
        if (mpvflow_rate_limiter_ready(&limiter, now, 120)) {
            if (has_last_submit)
                assert(now - last_submit_ns >= mpvflow_rate_limiter_interval_ns(120));
            mpvflow_rate_limiter_commit(&limiter, now, 120);
            last_submit_ns = now;
            has_last_submit = true;
            submits++;
        }
    }
    assert(submits >= 830 && submits <= 840);

    /* A correctly paced 120-Hz callback stream is admitted at the target. */
    mpvflow_rate_limiter_reset(&limiter);
    submits = 0;
    last_submit_ns = 0;
    has_last_submit = false;
    for (uint64_t now = 0; now < 10ULL * MPVFLOW_RATE_LIMIT_ONE; now += 8333334ULL) {
        if (mpvflow_rate_limiter_ready(&limiter, now, 120)) {
            if (has_last_submit)
                assert(now - last_submit_ns >= mpvflow_rate_limiter_interval_ns(120));
            mpvflow_rate_limiter_commit(&limiter, now, 120);
            last_submit_ns = now;
            has_last_submit = true;
            submits++;
        }
    }
    assert(submits >= 1199 && submits <= 1200);

    mpvflow_rate_limiter_reset(&limiter);
    assert(mpvflow_rate_limiter_ready(&limiter, 0, 120));
    mpvflow_rate_limiter_commit(&limiter, 0, 120);
    assert(!mpvflow_rate_limiter_ready(&limiter, 8333333ULL, 120));
    assert(mpvflow_rate_limiter_ready(&limiter, 8333334ULL, 120));
    mpvflow_rate_limiter_commit(&limiter, 8333334ULL, 120);
    assert(!mpvflow_rate_limiter_ready(&limiter, 16666667ULL, 120));
    assert(mpvflow_rate_limiter_ready(&limiter, 16666668ULL, 120));

    /* A long idle grants one frame only, not the previous bucket's two-frame burst. */
    assert(mpvflow_rate_limiter_ready(&limiter, 10000000000ULL, 120));
    mpvflow_rate_limiter_commit(&limiter, 10000000000ULL, 120);
    assert(!mpvflow_rate_limiter_ready(&limiter, 10001000000ULL, 120));
    assert(!mpvflow_rate_limiter_ready(&limiter, 10008333333ULL, 120));
    assert(mpvflow_rate_limiter_ready(&limiter, 10008333334ULL, 120));

    /* A target change uses the new rate from the last successful submit. */
    mpvflow_rate_limiter_reset(&limiter);
    mpvflow_rate_limiter_commit(&limiter, 0, 240);
    assert(!mpvflow_rate_limiter_ready(&limiter, 16666666ULL, 60));
    assert(mpvflow_rate_limiter_ready(&limiter, 16666667ULL, 60));
    mpvflow_rate_limiter_commit(&limiter, 16666667ULL, 60);
    assert(!mpvflow_rate_limiter_ready(&limiter, 24999999ULL, 120));
    assert(mpvflow_rate_limiter_ready(&limiter, 25000001ULL, 120));
    assert(!mpvflow_rate_limiter_ready(&limiter, 1ULL, 120));
    assert(mpvflow_rate_limiter_ready(&limiter, 25000001ULL, 0));
    mpvflow_rate_limiter_commit(&limiter, 25000001ULL, 0);
    assert(mpvflow_rate_limiter_ready(&limiter, 25000002ULL, 120));
    return 0;
}
"""
with tempfile.TemporaryDirectory(prefix="mpvflow-rate-limit-test-") as temp_dir:
    temp_path = Path(temp_dir)
    test_source = temp_path / "rate_limit_test.c"
    test_binary = temp_path / "rate_limit_test"
    test_source.write_text(rate_limit_test, encoding="utf-8")
    compile_rate_test = subprocess.run(
        ["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-I", str(rate_limit_header.parent),
         str(test_source), "-o", str(test_binary)],
        check=False,
        capture_output=True,
        text=True,
    )
    assert compile_rate_test.returncode == 0, (
        f"MPVFlow limiter C regression test failed to compile:\n{compile_rate_test.stdout}{compile_rate_test.stderr}"
    )
    run_rate_test = subprocess.run([str(test_binary)], check=False, capture_output=True, text=True)
    assert run_rate_test.returncode == 0, (
        f"MPVFlow limiter C regression test failed:\n{run_rate_test.stdout}{run_rate_test.stderr}"
    )

print("MPVFlow Android Vulkan/glslang native-build regression tests passed")
