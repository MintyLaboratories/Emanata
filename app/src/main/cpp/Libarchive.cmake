# libarchive's RAR readers (4 and 5, its own BSD implementations; no UnRAR code), built from
# its pinned release in a library of its own (archivejni, archive_jni.c). No other formats,
# filters, crypto library or iconv: encrypted RAR entries are refused.

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

# The read core and the two RAR readers; PPMd (RAR 4) and BLAKE2sp (RAR 5) are theirs. The RAR 4
# reader is the release's, modified to read solid archives (libarchive_rar/; diff it against the
# release's when moving to another one).
set(LIBARCHIVE_SOURCES
    archive_acl.c archive_blake2s_ref.c archive_blake2sp_ref.c archive_check_magic.c archive_crc32.c
    archive_entry.c archive_entry_sparse.c archive_entry_xattr.c archive_options.c archive_ppmd7.c
    archive_random.c archive_read.c archive_read_set_options.c
    archive_read_support_format_rar5.c archive_string.c archive_string_sprintf.c archive_time.c archive_util.c
    archive_version_details.c archive_virtual.c)
list(TRANSFORM LIBARCHIVE_SOURCES PREPEND "${libarchive_SOURCE_DIR}/libarchive/")
list(APPEND LIBARCHIVE_SOURCES "${CMAKE_CURRENT_LIST_DIR}/libarchive_rar/archive_read_support_format_rar.c")

add_library(archive_ro STATIC ${LIBARCHIVE_SOURCES})
target_compile_definitions(archive_ro PRIVATE "PLATFORM_CONFIG_H=\"archive_config.h\"")
target_include_directories(archive_ro
    PRIVATE "${CMAKE_CURRENT_LIST_DIR}/archive_config" "${libarchive_SOURCE_DIR}/contrib/android/config"
        "${libarchive_SOURCE_DIR}/contrib/android/include"
    PUBLIC "${libarchive_SOURCE_DIR}/libarchive")
# Third-party code: its warnings are not ours to fix.
target_compile_options(archive_ro PRIVATE -w -ffunction-sections -fdata-sections)
target_link_libraries(archive_ro PRIVATE z)

add_library(archivejni SHARED archive_jni.c)
target_link_libraries(archivejni PRIVATE archive_ro z log)
target_compile_options(archivejni PRIVATE -Wall -Wextra)
set_target_properties(archivejni PROPERTIES C_VISIBILITY_PRESET hidden)
target_link_options(archivejni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
