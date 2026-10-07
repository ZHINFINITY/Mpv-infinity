/* SPDX-License-Identifier: AGPL-3.0-or-later */
#ifndef MPV_INFINITY_RIFE_VFI_AHB_POLICY_H
#define MPV_INFINITY_RIFE_VFI_AHB_POLICY_H

// Pure policy helpers keep compile-time support separate from actual per-buffer
// support and from end-to-end playback acceptance.
static inline int rife_vfi_ahb_probe_runtime_supported(
    int android_api, int vulkan_device_ready, int ahb_import_extension,
    int foreign_queue_family_extension, int sampled_image_usage)
{
    return android_api >= 26 && vulkan_device_ready && ahb_import_extension &&
           foreign_queue_family_extension && sampled_image_usage;
}

// The GPU route remains unavailable until every decoder, tensor, output,
// renderer, timing, synchronization, and physical-device acceptance boundary
// is explicitly marked complete. Callers must not infer playback readiness
// from Vulkan or AHardwareBuffer extension support alone.
static inline int rife_vfi_gpu_resident_playback_supported(
    int retained_decoder_lease, int pts_pairing, int rife_tensor_adapter,
    int writable_output_ahb, int renderer_import, int producer_consumer_fences,
    int queue_lifecycle, int device_acceptance)
{
    return retained_decoder_lease && pts_pairing && rife_tensor_adapter &&
           writable_output_ahb && renderer_import && producer_consumer_fences &&
           queue_lifecycle && device_acceptance;
}

#endif // MPV_INFINITY_RIFE_VFI_AHB_POLICY_H
