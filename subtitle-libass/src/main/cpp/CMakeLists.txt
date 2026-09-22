cmake_minimum_required(VERSION 3.22.1)
project(media3_subtitle_libass)

set(LIBASS_ANDROID_DIR "${CMAKE_BINARY_DIR}/libass-android-src" CACHE PATH "Pinned Android libass source checkout")
if(NOT EXISTS "${LIBASS_ANDROID_DIR}/lib_ass/src/main/cpp/libass-cmake/CMakeLists.txt")
  execute_process(
    COMMAND git clone --depth 1 --branch master --recurse-submodules https://github.com/peerless2012/libass-android.git "${LIBASS_ANDROID_DIR}"
    RESULT_VARIABLE clone_result
  )
  if(NOT clone_result EQUAL 0)
    message(FATAL_ERROR "Unable to fetch pinned Android libass source")
  endif()
endif()

set(LIBASS_CMAKE_ROOT "${LIBASS_ANDROID_DIR}/lib_ass/src/main/cpp/libass-cmake")
foreach(autotools_dir IN ITEMS
    "${LIBASS_CMAKE_ROOT}/src/expat/expat"
    "${LIBASS_CMAKE_ROOT}/src/unibreak"
    "${LIBASS_CMAKE_ROOT}/src/fribidi"
    "${LIBASS_CMAKE_ROOT}/src/fontconfig"
    "${LIBASS_CMAKE_ROOT}/src/ass")
  if(EXISTS "${autotools_dir}/configure.ac" AND NOT EXISTS "${autotools_dir}/configure")
    execute_process(COMMAND autoreconf -fi WORKING_DIRECTORY "${autotools_dir}" RESULT_VARIABLE autoreconf_result)
    if(NOT autoreconf_result EQUAL 0)
      message(FATAL_ERROR "Unable to generate configure script in ${autotools_dir}")
    endif()
  endif()
endforeach()

file(GLOB_RECURSE LIBASS_CMAKE_FILES "${LIBASS_CMAKE_ROOT}/*.cmake")
foreach(recipe IN LISTS LIBASS_CMAKE_FILES)
  file(READ "${recipe}" recipe_text)
  string(REGEX REPLACE "\\\\?\\$\\{CMAKE_BINARY_DIR\\}" "${CMAKE_BINARY_DIR}" recipe_text "${recipe_text}")
  string(REPLACE "{CMAKE_BINARY_DIR}" "${CMAKE_BINARY_DIR}" recipe_text "${recipe_text}")
  if(NOT CMAKE_ANDROID_ARCH STREQUAL "arm64")
    string(REPLACE "--enable-asm" "--disable-asm" recipe_text "${recipe_text}")
  endif()
  file(WRITE "${recipe}" "${recipe_text}")
endforeach()

add_subdirectory("${LIBASS_CMAKE_ROOT}" libass-cmake-build)
add_library(media3_subtitle_libass SHARED libass_jni.cpp)
add_dependencies(media3_subtitle_libass ep_ass)
target_compile_features(media3_subtitle_libass PRIVATE cxx_std_17)
target_compile_definitions(media3_subtitle_libass PRIVATE MEDIA3_LIBASS_BRIDGE_VERSION=2 MEDIA3_LIBASS_HAS_NATIVE=1)
target_include_directories(media3_subtitle_libass PRIVATE
  "${LIBASS_ANDROID_DIR}/lib_ass/src/main/cpp/include"
  "${CMAKE_BINARY_DIR}/include")
target_link_directories(media3_subtitle_libass PRIVATE "${CMAKE_BINARY_DIR}/lib")
target_link_libraries(media3_subtitle_libass PRIVATE
  "-Wl,--whole-archive" ass fribidi freetype fontconfig expat harfbuzz unibreak "-Wl,--no-whole-archive"
  android log z)
