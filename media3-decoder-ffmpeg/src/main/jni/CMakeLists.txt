cmake_minimum_required(VERSION 3.22.1)
project(media3_ffmpeg_jni LANGUAGES CXX)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The Media3 DTS bridge is currently built for arm64-v8a only; got ${ANDROID_ABI}")
endif()

foreach(required_dir MEDIA3_FFMPEG_SOURCE_DIR MEDIA3_FFMPEG_RUNTIME_LIBS_DIR)
    if(NOT DEFINED ${required_dir} OR "${${required_dir}}" STREQUAL "")
        message(FATAL_ERROR "${required_dir} must be set by the Gradle preparation task")
    endif()
endforeach()

set(ffmpeg_include_dir "${MEDIA3_FFMPEG_SOURCE_DIR}")
set(ffmpeg_runtime_dir "${MEDIA3_FFMPEG_RUNTIME_LIBS_DIR}/${ANDROID_ABI}")
foreach(library avcodec avutil swresample)
    if(NOT EXISTS "${ffmpeg_runtime_dir}/lib${library}.so")
        message(FATAL_ERROR "Missing existing LGPL FFmpeg runtime library: ${ffmpeg_runtime_dir}/lib${library}.so")
    endif()
endforeach()

add_library(ffmpegJNI SHARED ffmpeg_jni.cc)
target_include_directories(ffmpegJNI PRIVATE "${ffmpeg_include_dir}")
target_link_directories(ffmpegJNI PRIVATE "${ffmpeg_runtime_dir}")
target_link_options(
    ffmpegJNI
    PRIVATE
    "-Wl,--no-undefined"
    "-Wl,-rpath-link,${ffmpeg_runtime_dir}"
)

find_library(android_log_library log REQUIRED)
target_link_libraries(ffmpegJNI PRIVATE avcodec swresample avutil ${android_log_library})
target_compile_features(ffmpegJNI PRIVATE cxx_std_11)
