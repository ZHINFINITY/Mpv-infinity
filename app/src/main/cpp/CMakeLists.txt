cmake_minimum_required(VERSION 3.22.1)

project("mpv_infinity_native" C CXX)

# Release-only native optimization shared by Mpv-infinity-owned JNI/helper targets.
# Keep this scoped to our small native targets; the prebuilt mpvlib AARs are
# optimized separately when their mpv/FFmpeg toolchain is built.
set(MPV_INFINITY_NATIVE_RELEASE_COMPILE_OPTIONS
    "$<$<CONFIG:Release>:-ffunction-sections>"
    "$<$<CONFIG:Release>:-fdata-sections>"
    "$<$<CONFIG:Release>:-fvisibility=hidden>"
)

set(MPV_INFINITY_NATIVE_RELEASE_LINK_OPTIONS
    "$<$<CONFIG:Release>:-Wl,-O1>"
    "$<$<CONFIG:Release>:-Wl,--gc-sections>"
    "$<$<CONFIG:Release>:-Wl,--icf=safe>"
    "$<$<CONFIG:Release>:-Wl,-z,max-page-size=16384>"
)

function(mpvinfinity_optimize_native_target target)
    target_compile_options(${target} PRIVATE ${MPV_INFINITY_NATIVE_RELEASE_COMPILE_OPTIONS})
    target_link_options(${target} PRIVATE ${MPV_INFINITY_NATIVE_RELEASE_LINK_OPTIONS})
endfunction()

# YTDLP execution wrapper for SDK 29+ bypass
add_executable(ytdl_wrapper ytdl_wrapper.c)
set_target_properties(ytdl_wrapper PROPERTIES OUTPUT_NAME "ytdl")
set_target_properties(ytdl_wrapper PROPERTIES PREFIX "lib" SUFFIX ".so")
mpvinfinity_optimize_native_target(ytdl_wrapper)

# QuickJS-NG CLI used by current yt-dlp releases for YouTube n/sig challenges.
# Package it as an executable .so beside libytdl.so so Android permits subprocess execution.
set(QUICKJS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs")
add_executable(
    qjs_runtime
    "${QUICKJS_DIR}/dtoa.c"
    "${QUICKJS_DIR}/gen/repl.c"
    "${QUICKJS_DIR}/gen/standalone.c"
    "${QUICKJS_DIR}/libregexp.c"
    "${QUICKJS_DIR}/libunicode.c"
    "${QUICKJS_DIR}/qjs.c"
    "${QUICKJS_DIR}/quickjs-libc.c"
    "${QUICKJS_DIR}/quickjs.c"
)
set_target_properties(
    qjs_runtime
    PROPERTIES
    OUTPUT_NAME "qjs"
    PREFIX "lib"
    SUFFIX ".so"
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    C_EXTENSIONS ON
    ENABLE_EXPORTS TRUE
)
target_include_directories(qjs_runtime PRIVATE "${QUICKJS_DIR}")
target_compile_definitions(qjs_runtime PRIVATE _GNU_SOURCE QUICKJS_NG_BUILD)
target_compile_options(qjs_runtime PRIVATE -funsigned-char)
target_link_libraries(qjs_runtime dl m)
mpvinfinity_optimize_native_target(qjs_runtime)

# Native Media3 shares the same clean-room motion pipeline as MPV Flow. Link
# only to a verified libplacebo Vulkan API exported by the selected MPV AAR.
option(MPVFLOW_ENABLE_VULKAN_BACKEND "Build the shared Vulkan Media3 Flow backend" OFF)
set(MPVFLOW_MPV_AAR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/mpvlib.aar"
    CACHE FILEPATH "Selected mpv AAR whose libplacebo API backs Media3 Flow")
if(MPVFLOW_ENABLE_VULKAN_BACKEND)
    if(NOT EXISTS "${MPVFLOW_MPV_AAR}")
        message(FATAL_ERROR "Vulkan Media3 Flow requires an mpv AAR: ${MPVFLOW_MPV_AAR}")
    endif()
    set(MPVFLOW_AAR_EXTRACT_DIR "${CMAKE_CURRENT_BINARY_DIR}/mpvflow-mpv-aar")
    file(ARCHIVE_EXTRACT
        INPUT "${MPVFLOW_MPV_AAR}"
        DESTINATION "${MPVFLOW_AAR_EXTRACT_DIR}"
        PATTERNS "jni/${ANDROID_ABI}/libmpv.so")
    set(MPVFLOW_PLACEBO_SO "${MPVFLOW_AAR_EXTRACT_DIR}/jni/${ANDROID_ABI}/libplacebo.so")
    set(MPVFLOW_MPV_SO "${MPVFLOW_AAR_EXTRACT_DIR}/jni/${ANDROID_ABI}/libmpv.so")
    execute_process(
        COMMAND "${CMAKE_COMMAND}" -E tar tf "${MPVFLOW_MPV_AAR}"
        RESULT_VARIABLE MPVFLOW_AAR_LIST_RESULT
        OUTPUT_VARIABLE MPVFLOW_AAR_ENTRIES
        ERROR_VARIABLE MPVFLOW_AAR_LIST_ERROR)
    if(NOT MPVFLOW_AAR_LIST_RESULT EQUAL 0)
        message(FATAL_ERROR "Cannot inspect selected MPV AAR ${MPVFLOW_MPV_AAR}: ${MPVFLOW_AAR_LIST_ERROR}")
    endif()
    string(REPLACE "\r" "" MPVFLOW_AAR_ENTRIES "${MPVFLOW_AAR_ENTRIES}")
    string(REPLACE "\n" ";" MPVFLOW_AAR_ENTRIES "${MPVFLOW_AAR_ENTRIES}")
    set(MPVFLOW_HAS_PLACEBO_SO FALSE)
    foreach(MPVFLOW_AAR_ENTRY IN LISTS MPVFLOW_AAR_ENTRIES)
        if(MPVFLOW_AAR_ENTRY STREQUAL "jni/${ANDROID_ABI}/libplacebo.so")
            set(MPVFLOW_HAS_PLACEBO_SO TRUE)
            break()
        endif()
    endforeach()
    if(MPVFLOW_HAS_PLACEBO_SO)
        file(ARCHIVE_EXTRACT
            INPUT "${MPVFLOW_MPV_AAR}"
            DESTINATION "${MPVFLOW_AAR_EXTRACT_DIR}"
            PATTERNS "jni/${ANDROID_ABI}/libplacebo.so")
        set(MPVFLOW_PLACEBO_API_SO "${MPVFLOW_PLACEBO_SO}")
    elseif(EXISTS "${MPVFLOW_MPV_SO}")
        # Existing project AARs may statically include and export libplacebo.
        # The custom Flow runtime instead packages libplacebo.so explicitly.
        set(MPVFLOW_PLACEBO_API_SO "${MPVFLOW_MPV_SO}")
    else()
        message(FATAL_ERROR "Selected MPV AAR has neither libplacebo.so nor libmpv.so for ${ANDROID_ABI}")
    endif()

    if(CMAKE_NM)
        set(MPVFLOW_NM_TOOL "${CMAKE_NM}")
    else()
        find_program(MPVFLOW_NM_TOOL NAMES llvm-nm nm)
    endif()
    if(NOT MPVFLOW_NM_TOOL)
        message(FATAL_ERROR "Cannot verify exported Vulkan API in ${MPVFLOW_PLACEBO_API_SO}: llvm-nm/nm unavailable")
    endif()
    execute_process(
        COMMAND "${MPVFLOW_NM_TOOL}" -D --defined-only "${MPVFLOW_PLACEBO_API_SO}"
        RESULT_VARIABLE MPVFLOW_NM_RESULT
        OUTPUT_VARIABLE MPVFLOW_PLACEBO_EXPORTS
        ERROR_VARIABLE MPVFLOW_NM_ERROR)
    if(NOT MPVFLOW_NM_RESULT EQUAL 0)
        message(FATAL_ERROR "Cannot inspect Vulkan API exports in ${MPVFLOW_PLACEBO_API_SO}: ${MPVFLOW_NM_ERROR}")
    endif()
    string(REPLACE "\r" "" MPVFLOW_PLACEBO_EXPORTS "${MPVFLOW_PLACEBO_EXPORTS}")
    string(REPLACE "\n" ";" MPVFLOW_PLACEBO_EXPORT_LINES "${MPVFLOW_PLACEBO_EXPORTS}")
    foreach(MPVFLOW_REQUIRED_PLACEBO_SYMBOL IN ITEMS
            pl_vulkan_default_params pl_vulkan_create pl_vulkan_destroy
            pl_vulkan_wrap pl_vulkan_hold_ex pl_vulkan_release_ex)
        set(MPVFLOW_SYMBOL_EXPORTED FALSE)
        foreach(MPVFLOW_EXPORT_LINE IN LISTS MPVFLOW_PLACEBO_EXPORT_LINES)
            if(MPVFLOW_EXPORT_LINE MATCHES "[ \t]${MPVFLOW_REQUIRED_PLACEBO_SYMBOL}(@[^ \t]+)?$")
                set(MPVFLOW_SYMBOL_EXPORTED TRUE)
                break()
            endif()
        endforeach()
        if(NOT MPVFLOW_SYMBOL_EXPORTED)
            message(FATAL_ERROR
                "Selected MPV AAR library ${MPVFLOW_PLACEBO_API_SO} does not export ${MPVFLOW_REQUIRED_PLACEBO_SYMBOL}; refusing to build a nonfunctional Vulkan backend")
        endif()
    endforeach()

    add_library(mpvflow_placebo_api_from_aar SHARED IMPORTED GLOBAL)
    set_target_properties(mpvflow_placebo_api_from_aar PROPERTIES
        IMPORTED_LOCATION "${MPVFLOW_PLACEBO_API_SO}")

    add_library(media3flow_vulkan SHARED
        media3flow_vulkan_jni.cpp
        mpvflow/mpvflow_gpu.c)
    set_target_properties(media3flow_vulkan PROPERTIES
        C_STANDARD 11
        C_STANDARD_REQUIRED ON
        C_EXTENSIONS ON
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON)
    target_include_directories(media3flow_vulkan PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplacebo/include"
        "${CMAKE_CURRENT_SOURCE_DIR}/mpvflow")
    target_link_libraries(media3flow_vulkan PRIVATE
        mpvflow_placebo_api_from_aar
        android
        EGL
        GLESv3
        log
        vulkan)
    mpvinfinity_optimize_native_target(media3flow_vulkan)
endif()
