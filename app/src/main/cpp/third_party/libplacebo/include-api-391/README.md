# FongMi libplacebo API-391 headers

This is the complete arm64 public-header snapshot packaged by `mpvlibAndroid` FongMi renderer SDK v1.1.8.2, built from FongMi/libplacebo commit `63659d96d1bff7d7a5a1368e9538056a193fc6fb`. `MPVRX_SDK_HEADERS.sha256` preserves the original 36-file archive inventory; every upstream hash was verified before local adaptation. `libplacebo/config.h.in` is the exact pinned source template.

The archive's generated `config.h` describes a different renderer-SDK build that enabled shaderc and LCMS. The local config keeps its API-391 version and FongMi/Vulkan/OpenGL capabilities, but matches the shared MPV runtime build's feature profile: glslang, Vulkan and OpenGL enabled; shaderc and LCMS undefined. This matches the existing API-387 shared-runtime profile and the flags in `scripts/build-mpvflow-mpv-runtime.sh`. The header regression reverses those three config-line adaptations and verifies the original SDK hash, as well as checking the local config against the API-387 runtime feature profile.

`libplacebo/vulkan.h` contains only the two app-owned swapchain-diagnostics declarations inserted by `add_libplacebo_swapchain_diagnostics_api()` in `scripts/prepare-mpvflow.py`, matching the declarations added to the built runtime. `FONGMI_HEADERS.sha256` records all 37 local headers, including the custom runtime config, exact source template and diagnostics declarations.

CMake selects this directory only when the selected MPV AAR exports `pl_log_create_391`. The Native Media3 Vulkan target dynamically links to the same `libplacebo.so` from that AAR, so matching the full API-391 header set is required; the API-391 verifier-only marker is not a replacement for these headers. The C/C++ interpolation implementation is unchanged. Host-only ABI tests confirm `pl_desc` is 40 bytes, `pl_desc_binding` is 16, `pl_shader_desc` is 72, and API-391 `pl_render_params` is 312 bytes with `frame_interp_params` at offset 48.

All upstream libplacebo notices are retained. This code is distributed under the GNU Lesser General Public License, version 2.1 or later; see `app/src/main/cpp/third_party/libplacebo/COPYING`.
