/*
 * libarchive's configuration for the app's reader (Libarchive.cmake): the Android
 * configuration libarchive ships, plus liblzma's and zlib's headers, which the build
 * provides. No iconv: name conversions use libarchive's own UTF-8 and UTF-16 code.
 */
#ifndef EMANATA_ARCHIVE_CONFIG_H
#define EMANATA_ARCHIVE_CONFIG_H

#include "android.h"

#define HAVE_LZMA_H 1
#define HAVE_LIBLZMA 1
#define HAVE_ZLIB_H 1
#define HAVE_LIBZ 1

#endif
