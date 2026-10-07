#include "rife_vfi_ahb_policy.h"
#include <assert.h>

int main(void)
{
    assert(!rife_vfi_ahb_probe_runtime_supported(25, 1, 1, 1, 1));
    assert(!rife_vfi_ahb_probe_runtime_supported(36, 0, 1, 1, 1));
    assert(!rife_vfi_ahb_probe_runtime_supported(36, 1, 0, 1, 1));
    assert(!rife_vfi_ahb_probe_runtime_supported(36, 1, 1, 0, 1));
    assert(!rife_vfi_ahb_probe_runtime_supported(36, 1, 1, 1, 0));
    assert(rife_vfi_ahb_probe_runtime_supported(36, 1, 1, 1, 1));

    // Even with Vulkan/AHB input support, the full playback route must remain
    // disabled until all eight integration and on-device acceptance checks pass.
    assert(!rife_vfi_gpu_resident_playback_supported(0, 1, 1, 1, 1, 1, 1, 1));
    assert(!rife_vfi_gpu_resident_playback_supported(1, 0, 1, 1, 1, 1, 1, 1));
    assert(!rife_vfi_gpu_resident_playback_supported(1, 1, 0, 1, 1, 1, 1, 1));
    assert(!rife_vfi_gpu_resident_playback_supported(1, 1, 1, 0, 1, 1, 1, 1));
    assert(!rife_vfi_gpu_resident_playback_supported(1, 1, 1, 1, 0, 1, 1, 1));
    assert(!rife_vfi_gpu_resident_playback_supported(1, 1, 1, 1, 1, 0, 1, 1));
    assert(!rife_vfi_gpu_resident_playback_supported(1, 1, 1, 1, 1, 1, 0, 1));
    assert(!rife_vfi_gpu_resident_playback_supported(1, 1, 1, 1, 1, 1, 1, 0));
    assert(rife_vfi_gpu_resident_playback_supported(1, 1, 1, 1, 1, 1, 1, 1));
    return 0;
}
