#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
RIFE_DIR="${RIFE_SOURCE_DIR:-$ROOT/rife-project}"
ANVIL_DIR="${ANVIL_SOURCE_DIR:-$ROOT/anvil-project}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$RIFE_DIR/src" && -f "$RIFE_DIR/src/rife.cpp" ]] || { echo "Missing pinned RIFE source: $RIFE_DIR" >&2; exit 1; }
[[ -n "$ANDROID_HOME" && -d "$ANDROID_HOME/ndk" ]] || { echo "ANDROID_HOME with an installed NDK is required" >&2; exit 1; }

NDK_VERSION="${ANDROID_NDK_VERSION:-27.3.13750724}"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || { echo "Missing Android NDK toolchain: $NDK" >&2; exit 1; }
PREFIX="${RIFE_PREFIX:-$ANVIL_DIR/buildscripts/prefix/arm64}"
BUILD_DIR="${RIFE_BUILD_DIR:-$RIFE_DIR/build-android-arm64}"

# RIFE upstream's CMake project builds a command-line executable. Convert only
# that target into a shared library while retaining upstream's shader generation
# and ncnn/libwebp configuration.
git -C "$RIFE_DIR" submodule update --init --recursive
python3 - "$RIFE_DIR/src/CMakeLists.txt" "$RIFE_DIR/src/rife_vfi_bridge.cpp" "$ROOT/app/src/main/cpp/rife/rife_vfi_bridge.cpp" <<'PY'
from pathlib import Path
import sys

cmake_path, bridge_target, bridge_source = map(Path, sys.argv[1:])
text = cmake_path.read_text()
if "RIFE_VFI_ANDROID_SHARED_TARGET" not in text:
    vulkan_anchor = "find_package(Vulkan REQUIRED)"
    if text.count(vulkan_anchor) != 1:
        raise SystemExit("Expected one RIFE Vulkan discovery anchor; refusing an unverified patch")
    text = text.replace(
        vulkan_anchor,
        "if(ANDROID)\n    set(Vulkan_LIBRARY vulkan)\nelse()\n    find_package(Vulkan REQUIRED)\nendif()",
        1,
    )
    old_target = '''add_executable(rife-ncnn-vulkan
    main.cpp
    rife.cpp
    warp.cpp
)

add_dependencies(rife-ncnn-vulkan generate-spirv)'''
    new_target = '''add_library(rife_vfi SHARED
    rife_vfi_bridge.cpp
    rife.cpp
    warp.cpp
)

add_dependencies(rife_vfi generate-spirv)
# RIFE_VFI_ANDROID_SHARED_TARGET'''
    if text.count(old_target) != 1:
        raise SystemExit("Expected exactly one upstream RIFE executable target; refusing an unverified patch")
    text = text.replace(old_target, new_target, 1)
    old_link = "target_link_libraries(rife-ncnn-vulkan ${RIFE_LINK_LIBRARIES})"
    new_link = '''target_link_libraries(rife_vfi PRIVATE ${RIFE_LINK_LIBRARIES})
install(TARGETS rife_vfi LIBRARY DESTINATION lib)
install(FILES rife_vfi.h DESTINATION include)'''
    if text.count(old_link) != 1:
        raise SystemExit("Expected exactly one upstream RIFE link target; refusing an unverified patch")
    text = text.replace(old_link, new_link, 1)
    cmake_path.write_text(text)

bridge_source = bridge_source.resolve()
bridge_target.write_text(bridge_source.read_text())

rife_cpp_path = cmake_path.parent / "rife.cpp"
rife_cpp = rife_cpp_path.read_text()
if "// RIFE_VFI_NULL_SAFE_UHD_DESTROY" not in rife_cpp:
    old = (
        "    if (uhd_mode)\n"
        "    {\n"
        "        rife_uhd_downscale_image->destroy_pipeline(flownet.opt);\n"
        "        delete rife_uhd_downscale_image;\n"
        "\n"
        "        rife_uhd_upscale_flow->destroy_pipeline(flownet.opt);\n"
        "        delete rife_uhd_upscale_flow;\n"
        "\n"
        "        rife_uhd_double_flow->destroy_pipeline(flownet.opt);\n"
        "        delete rife_uhd_double_flow;\n"
        "    }"
    )
    new = (
        "    if (uhd_mode)\n"
        "    {\n"
        "        if (rife_uhd_downscale_image) {\n"
        "            rife_uhd_downscale_image->destroy_pipeline(flownet.opt);\n"
        "            delete rife_uhd_downscale_image;\n"
        "        }\n"
        "        if (rife_uhd_upscale_flow) {\n"
        "            rife_uhd_upscale_flow->destroy_pipeline(flownet.opt);\n"
        "            delete rife_uhd_upscale_flow;\n"
        "        }\n"
        "        if (rife_uhd_double_flow) {\n"
        "            rife_uhd_double_flow->destroy_pipeline(flownet.opt);\n"
        "            delete rife_uhd_double_flow;\n"
        "        }\n"
        "    }\n"
        "    // RIFE_VFI_NULL_SAFE_UHD_DESTROY"
    )
    if rife_cpp.count(old) != 1:
        raise SystemExit("Expected one RIFE UHD destructor block; refusing an unverified patch")
    rife_cpp_path.write_text(rife_cpp.replace(old, new, 1))

ncnn_cmake_path = cmake_path.parent / "ncnn/src/CMakeLists.txt"
ncnn_cmake = ncnn_cmake_path.read_text()
if "# RIFE_VFI_ANDROID_VULKAN_TARGET" not in ncnn_cmake:
    old = "    find_package(Vulkan QUIET)\n    if(NOT Vulkan_FOUND)"
    new = (
        "    if(ANDROID)\n"
        "        if(NOT TARGET Vulkan::Vulkan)\n"
        "            add_library(Vulkan::Vulkan INTERFACE IMPORTED GLOBAL)\n"
        "            set_property(TARGET Vulkan::Vulkan PROPERTY INTERFACE_LINK_LIBRARIES vulkan)\n"
        "        endif()\n"
        "        set(Vulkan_FOUND TRUE)\n"
        "    else()\n"
        "        find_package(Vulkan QUIET)\n"
        "    endif()\n"
        "    # RIFE_VFI_ANDROID_VULKAN_TARGET\n"
        "    if(NOT Vulkan_FOUND)"
    )
    if ncnn_cmake.count(old) != 1:
        raise SystemExit("Expected one ncnn Vulkan discovery anchor; refusing an unverified patch")
    ncnn_cmake_path.write_text(ncnn_cmake.replace(old, new, 1))
PY

# Stage the source-level C ABI header where the upstream CMake target can install it.
cp "$ROOT/app/src/main/cpp/rife/rife_vfi.h" "$RIFE_DIR/src/rife_vfi.h"

cmake -S "$RIFE_DIR/src" -B "$BUILD_DIR" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_CXX_STANDARD=17 \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
  -DNCNN_VULKAN=ON \
  -DNCNN_VULKAN_ONLINE_SPIRV=ON \
  -DNCNN_BUILD_TESTS=OFF \
  -DNCNN_BUILD_TOOLS=OFF \
  -DNCNN_BUILD_EXAMPLES=OFF \
  -DWEBP_BUILD_ANIM_UTILS=OFF \
  -DWEBP_BUILD_CWEBP=OFF \
  -DWEBP_BUILD_DWEBP=OFF
cmake --build "$BUILD_DIR" --target rife_vfi --parallel "${RIFE_BUILD_CORES:-2}"
cmake --install "$BUILD_DIR"

python3 "$ROOT/scripts/stage-rife-model.py" \
  --source "$RIFE_DIR/models/rife-v4.6" \
  --license "$RIFE_DIR/LICENSE" \
  --ncnn-license "$RIFE_DIR/src/ncnn/LICENSE.txt" \
  --webp-license "$RIFE_DIR/src/libwebp/COPYING" \
  --destination "$ROOT/app/src/main/assets/rife-v4.6"

echo "Built arm64 RIFE/ncnn Vulkan runtime at $PREFIX/lib/librife_vfi.so"
echo "Staged verified RIFE-v4.6 model assets in app/src/main/assets/rife-v4.6"
