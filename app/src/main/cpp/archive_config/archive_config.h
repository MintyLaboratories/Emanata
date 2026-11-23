/*
 * libarchive's configuration for the app's RAR reader (Libarchive.cmake): the Android
 * configuration libarchive ships, plus zlib's header (its CRC-32). No iconv: name
 * conversions use libarchive's own UTF-8 and UTF-16 code.
 */
#ifndef EMANATA_ARCHIVE_CONFIG_H
#define EMANATA_ARCHIVE_CONFIG_H

#include "android.h"

#define HAVE_ZLIB_H 1
#define HAVE_LIBZ 1

#endif
