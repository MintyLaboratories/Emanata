# Host unit tests of native logic that needs no Android: the JPEG XL parallel runner
# (jxl_runner.c) and the GPU resampler's chunk order (resample_chunks.c). Linux (or WSL)
# with a C compiler:
#
#     cmake -S app/src/test/cpp -B build/native-test && cmake --build build/native-test && ctest --test-dir build/native-test
cmake_minimum_required(VERSION 3.22)
project(native_tests C)

set(CMAKE_C_STANDARD 11)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
include(FetchContent)

# The public headers of the libjxl release the app compiles against (src/main/cpp/CMakeLists.txt).
FetchContent_Declare(
    libjxl
    URL "https://github.com/libjxl/libjxl/archive/refs/tags/v0.12.0.tar.gz"
    URL_HASH SHA256=03e9be69a30be4011f559da75328b6d7cea8ad921fabfbd551ce10bf45cdc992
    SOURCE_SUBDIR headers-only
)
FetchContent_MakeAvailable(libjxl)

set(MAIN "${CMAKE_CURRENT_LIST_DIR}/../../main/cpp")
find_package(Threads REQUIRED)

enable_testing()
add_executable(jxl_runner_test jxl_runner_test.c "${MAIN}/jxl_runner.c")
target_include_directories(jxl_runner_test PRIVATE "${MAIN}" "${MAIN}/jxl_headers" "${libjxl_SOURCE_DIR}/lib/include")
target_compile_options(jxl_runner_test PRIVATE -Wall -Wextra -Werror)
target_link_libraries(jxl_runner_test PRIVATE Threads::Threads)
add_test(NAME jxl_runner COMMAND jxl_runner_test)

add_executable(resample_chunks_test resample_chunks_test.c "${MAIN}/resample_chunks.c")
target_include_directories(resample_chunks_test PRIVATE "${MAIN}")
target_compile_options(resample_chunks_test PRIVATE -Wall -Wextra -Werror)
add_test(NAME resample_chunks COMMAND resample_chunks_test)
