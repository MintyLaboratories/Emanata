# libarchive's readers for RAR (4 and 5, its own BSD implementations), 7z and ZIP, built
# from its pinned release in a library of its own (archivejni, archive_jni.c). liblzma (xz,
# 0BSD) decodes 7z's LZMA and LZMA2 and ZIP's LZMA and XZ; the NDK's zlib decodes Deflate.
# No bzip2, zstd, LZ4, crypto library or iconv: 7z and ZIP entries packed with those fail to read.

FetchContent_Declare(
    xz
    EXCLUDE_FROM_ALL
    URL "https://github.com/tukaani-project/xz/releases/download/v5.8.4/xz-5.8.4.tar.gz"
    URL_HASH SHA256=0014c7886930454fe8bd4228665b51af55eeae560ea135c9c4cd33f55b2591d9
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
# liblzma alone, decoding only, single-threaded; no tools, translations or docs.
set(XZ_NLS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(XZ_THREADS no CACHE STRING "" FORCE)
set(XZ_ENCODERS "" CACHE STRING "" FORCE)
set(XZ_SANDBOX no CACHE STRING "" FORCE)
foreach(option XZ_TOOL_XZDEC XZ_TOOL_LZMADEC XZ_TOOL_LZMAINFO XZ_TOOL_XZ XZ_TOOL_SCRIPTS XZ_TOOL_SYMLINKS
        XZ_TOOL_SYMLINKS_LZMA XZ_DOC XZ_DOXYGEN XZ_LZIP_DECODER XZ_MICROLZMA_ENCODER XZ_MICROLZMA_DECODER)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
FetchContent_MakeAvailable(xz)

# Only the sources are used: SOURCE_SUBDIR names a folder without a build of its own, so
# libarchive's configure checks never run; contrib/android/config/android.h configures it.
FetchContent_Declare(
    libarchive
    URL "https://github.com/libarchive/libarchive/releases/download/v3.8.11/libarchive-3.8.11.tar.gz"
    URL_HASH SHA256=900fe9fc7237c2b04f090f3c3648de8fe597525771f5035016537ce2d667d12b
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    SOURCE_SUBDIR contrib/android/include
)
FetchContent_MakeAvailable(libarchive)

set(LIBARCHIVE_SOURCES
    archive_acl.c archive_blake2s_ref.c archive_blake2sp_ref.c archive_check_magic.c archive_crc32.c archive_cryptor.c
    archive_digest.c archive_entry.c archive_entry_sparse.c archive_entry_stat.c archive_entry_xattr.c
    archive_hmac.c archive_options.c archive_ppmd7.c archive_ppmd8.c archive_random.c archive_rb.c
    archive_read.c archive_read_add_passphrase.c archive_read_open_fd.c archive_read_set_options.c
    archive_read_support_format_7zip.c archive_read_support_format_rar.c archive_read_support_format_rar5.c
    archive_read_support_format_zip.c archive_string.c archive_string_sprintf.c archive_time.c archive_util.c
    archive_version_details.c archive_virtual.c)
list(TRANSFORM LIBARCHIVE_SOURCES PREPEND "${libarchive_SOURCE_DIR}/libarchive/")

add_library(archive_ro STATIC ${LIBARCHIVE_SOURCES})
target_compile_definitions(archive_ro PRIVATE "PLATFORM_CONFIG_H=\"archive_config.h\"")
target_include_directories(archive_ro
    PRIVATE "${CMAKE_CURRENT_LIST_DIR}/archive_config" "${libarchive_SOURCE_DIR}/contrib/android/config"
        "${libarchive_SOURCE_DIR}/contrib/android/include"
    PUBLIC "${libarchive_SOURCE_DIR}/libarchive" "${xz_SOURCE_DIR}/src/liblzma/api")
# Third-party code: its warnings are not ours to fix.
target_compile_options(archive_ro PRIVATE -w -ffunction-sections -fdata-sections)
target_link_libraries(archive_ro PRIVATE liblzma z)

add_library(archivejni SHARED archive_jni.c)
target_link_libraries(archivejni PRIVATE archive_ro liblzma z log)
target_compile_options(archivejni PRIVATE -Wall -Wextra)
set_target_properties(archivejni PROPERTIES C_VISIBILITY_PRESET hidden)
target_link_options(archivejni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
