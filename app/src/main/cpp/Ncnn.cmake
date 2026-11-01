# ncnn (neural network inference) with Vulkan compute, built from its pinned full-source
# release, which carries glslang (ncnn compiles its compute shaders at run time). Only the
# library: no tools, examples, benchmark, tests, Python or C API. Defines the ncnn target.

FetchContent_Declare(
    ncnn
    EXCLUDE_FROM_ALL
    URL "https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-full-source.zip"
    URL_HASH SHA256=754659d6fe65545cf2ef4483ffb84526fea631f8764c44b150f1601d0fb4004b
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)

# The release's own version, not the build date ncnn stamps by default.
set(NCNN_VERSION 20260526)
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
set(NCNN_SHARED_LIB OFF CACHE BOOL "" FORCE)
foreach(option NCNN_BUILD_TOOLS NCNN_BUILD_EXAMPLES NCNN_BUILD_BENCHMARK NCNN_BUILD_TESTS NCNN_PYTHON NCNN_INSTALL_SDK
        NCNN_C_API NCNN_PLATFORM_API NCNN_PIXEL NCNN_PIXEL_ROTATE NCNN_PIXEL_AFFINE NCNN_PIXEL_DRAWING NCNN_SIMPLEOCV)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
# x86_64 is for emulators only: the baseline SSE2 code, without per-CPU variants.
if(CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64")
    set(NCNN_RUNTIME_CPU OFF CACHE BOOL "" FORCE)
    set(NCNN_AVX OFF CACHE BOOL "" FORCE)
endif()

FetchContent_MakeAvailable(ncnn)
