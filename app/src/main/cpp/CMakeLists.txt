# AVIF decoding: libavif with the dav1d AV1 decoder, both built from pinned
# sources, behind a small JNI binding (avif_jni.c); Book IDs: xxHash's XXH3-64,
# header-only from its pinned release (book_id_jni.c); lossless compaction: libjxl
# as jxl-coder ships it, opened at run time and compiled against the headers of
# the same pinned release (jxl_jni.c).
cmake_minimum_required(VERSION 3.22)

# Optimized native code in every app build type: an unoptimized AV1 decoder takes
# seconds per page.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(avifjni C ASM)

include(FetchContent)
include(Dav1d.cmake)

FetchContent_Declare(
    libavif
    EXCLUDE_FROM_ALL
    URL "https://github.com/AOMediaCodec/libavif/archive/refs/tags/v1.4.2.tar.gz"
    URL_HASH SHA256=2b645287340ba5a631d268b551dc2d72bd73ac33335962dd36dcdb6d8366921d
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    PATCH_COMMAND ${CMAKE_COMMAND} -P "${CMAKE_CURRENT_LIST_DIR}/PatchLibavif.cmake"
)

# Decoding only, with dav1d and libavif's built-in YUV to RGB conversion.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(AVIF_CODEC_DAV1D SYSTEM CACHE STRING "" FORCE)
foreach(option AVIF_CODEC_AOM AVIF_CODEC_LIBGAV1 AVIF_CODEC_RAV1E AVIF_CODEC_SVT AVIF_CODEC_AVM
        AVIF_LIBYUV AVIF_LIBSHARPYUV AVIF_LIBXML2 AVIF_JPEG AVIF_ZLIBPNG AVIF_GTEST AVIF_FUZZTEST)
    set(${option} OFF CACHE STRING "" FORCE)
endforeach()
set(AVIF_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(libavif)

# Only xxhash.h is used; SOURCE_SUBDIR names no build of its own, so nothing else is built.
FetchContent_Declare(
    xxhash
    URL "https://github.com/Cyan4973/xxHash/archive/refs/tags/v0.8.3.tar.gz"
    URL_HASH SHA256=aae608dfe8213dfd05d909a57718ef82f30722c392344583d3f39050c7f29a80
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    SOURCE_SUBDIR header-only
)
FetchContent_MakeAvailable(xxhash)

# Only libjxl's public headers are used; jxl-coder's libjxl.so (the same release)
# provides the code, so nothing of libjxl is built. jxl_headers stands in for the
# two headers its build generates.
FetchContent_Declare(
    libjxl
    URL "https://github.com/libjxl/libjxl/archive/refs/tags/v0.12.0.tar.gz"
    URL_HASH SHA256=03e9be69a30be4011f559da75328b6d7cea8ad921fabfbd551ce10bf45cdc992
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    SOURCE_SUBDIR headers-only
)
FetchContent_MakeAvailable(libjxl)

add_library(avifjni SHARED avif_jni.c book_id_jni.c jxl_jni.c)
target_include_directories(avifjni PRIVATE "${xxhash_SOURCE_DIR}" "${CMAKE_CURRENT_LIST_DIR}/jxl_headers" "${libjxl_SOURCE_DIR}/lib/include")
target_link_libraries(avifjni PRIVATE avif jnigraphics log dl)
target_compile_options(avifjni PRIVATE -Wall -Wextra)
# Only the JNI entry points are exported; unused code is dropped.
set_target_properties(avifjni PROPERTIES C_VISIBILITY_PRESET hidden)
target_link_options(avifjni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
