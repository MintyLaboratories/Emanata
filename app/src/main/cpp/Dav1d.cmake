# dav1d (the AV1 decoder) built with CMake for Android, following its meson
# build: the common sources, the bit-depth templates compiled once for 8 and once
# for 16 bits per component, and the NEON assembly on arm64. x86_64 (emulators
# only) is built without assembly, which would need NASM. Defines dav1d::dav1d.

FetchContent_Declare(
    dav1d
    URL "https://downloads.videolan.org/pub/videolan/dav1d/1.5.4/dav1d-1.5.4.tar.xz"
    URL_HASH SHA256=686616b7c69eb88d44459391ab25cac13b6647a3b288835c5784e71c1514a5c5
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(dav1d)

set(DAV1D_DIR "${dav1d_SOURCE_DIR}")
set(DAV1D_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/dav1d-config")

if(CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    set(DAV1D_AARCH64 1)
    set(DAV1D_X86_64 0)
    set(DAV1D_ASM 1)
elseif(CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64")
    set(DAV1D_AARCH64 0)
    set(DAV1D_X86_64 1)
    set(DAV1D_ASM 0)
else()
    message(FATAL_ERROR "dav1d: unsupported ABI ${CMAKE_SYSTEM_PROCESSOR}")
endif()

# What meson's checks find with the NDK's clang and bionic (aligned_alloc only
# from API 28, so left out for minSdk 26).
file(WRITE "${DAV1D_GENERATED}/config.h" "\
#pragma once
#define ARCH_AARCH64 ${DAV1D_AARCH64}
#define ARCH_ARM 0
#define ARCH_LOONGARCH 0
#define ARCH_LOONGARCH32 0
#define ARCH_LOONGARCH64 0
#define ARCH_PPC64LE 0
#define ARCH_RISCV 0
#define ARCH_RV32 0
#define ARCH_RV64 0
#define ARCH_X86 ${DAV1D_X86_64}
#define ARCH_X86_32 0
#define ARCH_X86_64 ${DAV1D_X86_64}
#define AS_ARCH_LEVEL armv8.6-a
#define CONFIG_16BPC 1
#define CONFIG_8BPC 1
#define CONFIG_LOG 0
#define ENDIANNESS_BIG 0
#define HAVE_ALIGNED_ALLOC 0
#define HAVE_ASM ${DAV1D_ASM}
#define HAVE_AS_ARCHEXT_DOTPROD_DIRECTIVE 1
#define HAVE_AS_ARCHEXT_I8MM_DIRECTIVE 1
#define HAVE_AS_ARCHEXT_SVE2_DIRECTIVE 1
#define HAVE_AS_ARCHEXT_SVE_DIRECTIVE 1
#define HAVE_AS_ARCH_DIRECTIVE 1
#define HAVE_AS_FUNC 0
#define HAVE_C11_GENERIC 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_DLSYM 0
#define HAVE_DOTPROD 1
#define HAVE_ELF_AUX_INFO 0
#define HAVE_GETAUXVAL 1
#define HAVE_I8MM 1
#define HAVE_IO_H 0
#define HAVE_MEMALIGN 1
#define HAVE_POSIX_MEMALIGN 1
#define HAVE_PTHREAD_GETAFFINITY_NP 0
#define HAVE_PTHREAD_NP_H 0
#define HAVE_PTHREAD_SETAFFINITY_NP 0
#define HAVE_PTHREAD_SETNAME_NP 1
#define HAVE_PTHREAD_SET_NAME_NP 0
#define HAVE_SIGACTION 1
#define HAVE_SVE 1
#define HAVE_SVE2 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_UNISTD_H 1
#define TRIM_DSP_FUNCTIONS 1
")
file(WRITE "${DAV1D_GENERATED}/vcs_version.h" "#define DAV1D_VERSION \"1.5.4\"\n")

set(DAV1D_SOURCES
    cdf.c cpu.c ctx.c data.c decode.c dequant_tables.c getbits.c intra_edge.c itx_1d.c lf_mask.c lib.c log.c
    mem.c msac.c obu.c pal.c picture.c qm.c ref.c refmvs.c scan.c tables.c thread_task.c warpmv.c wedge.c
)
set(DAV1D_TEMPLATES
    cdef_apply_tmpl.c cdef_tmpl.c fg_apply_tmpl.c filmgrain_tmpl.c ipred_prepare_tmpl.c ipred_tmpl.c itx_tmpl.c
    lf_apply_tmpl.c loopfilter_tmpl.c looprestoration_tmpl.c lr_apply_tmpl.c mc_tmpl.c recon_tmpl.c
)
if(DAV1D_ASM)
    list(APPEND DAV1D_SOURCES
        arm/cpu.c
        arm/64/itx.S arm/64/looprestoration_common.S arm/64/msac.S arm/64/refmvs.S
        arm/64/cdef.S arm/64/filmgrain.S arm/64/ipred.S arm/64/loopfilter.S arm/64/looprestoration.S arm/64/mc.S
        arm/64/mc_dotprod.S
        arm/64/cdef16.S arm/64/filmgrain16.S arm/64/ipred16.S arm/64/itx16.S arm/64/loopfilter16.S
        arm/64/looprestoration16.S arm/64/mc16.S arm/64/mc16_sve.S
    )
endif()
list(TRANSFORM DAV1D_SOURCES PREPEND "${DAV1D_DIR}/src/")
list(TRANSFORM DAV1D_TEMPLATES PREPEND "${DAV1D_DIR}/src/")

set(DAV1D_INCLUDES "${DAV1D_GENERATED}" "${DAV1D_DIR}" "${DAV1D_DIR}/include" "${DAV1D_DIR}/include/dav1d")
set(DAV1D_C_FLAGS -fomit-frame-pointer -ffast-math -fvisibility=hidden -Wno-unused-parameter -Wno-missing-field-initializers)
if(DAV1D_AARCH64)
    list(APPEND DAV1D_C_FLAGS -fno-align-functions)
endif()
set(DAV1D_FLAGS "$<$<COMPILE_LANGUAGE:C>:${DAV1D_C_FLAGS}>")

foreach(bits 8 16)
    add_library(dav1d_bitdepth_${bits} OBJECT ${DAV1D_TEMPLATES})
    target_include_directories(dav1d_bitdepth_${bits} PRIVATE ${DAV1D_INCLUDES})
    target_compile_definitions(dav1d_bitdepth_${bits} PRIVATE BITDEPTH=${bits})
    target_compile_options(dav1d_bitdepth_${bits} PRIVATE ${DAV1D_FLAGS})
    set_target_properties(dav1d_bitdepth_${bits} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endforeach()

add_library(dav1d STATIC ${DAV1D_SOURCES} $<TARGET_OBJECTS:dav1d_bitdepth_8> $<TARGET_OBJECTS:dav1d_bitdepth_16>)
target_include_directories(dav1d PRIVATE ${DAV1D_INCLUDES})
target_include_directories(dav1d INTERFACE "${DAV1D_DIR}/include")
target_compile_options(dav1d PRIVATE ${DAV1D_FLAGS})
set_target_properties(dav1d PROPERTIES POSITION_INDEPENDENT_CODE ON)

# The name libavif looks for (AVIF_CODEC_DAV1D=SYSTEM uses an existing target).
add_library(dav1d::dav1d INTERFACE IMPORTED GLOBAL)
target_link_libraries(dav1d::dav1d INTERFACE dav1d)
set_target_properties(dav1d::dav1d PROPERTIES AVIF_LOCAL ON)
