package com.infinity.comicreader.util

import com.infinity.comicreader.data.EntryType

/**
 * Recognised formats: images (jpg/jpeg/png/gif/bmp/webp/avif/heic/heif/jxl), the
 * archives zip/cbz, rar/cbr, 7z/cb7, tar/cbt, plus pdf. Extensions only pick what to list;
 * the bytes decide how a file is read ([com.infinity.comicreader.reader.ImageKind]).
 */
object Formats {
    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "avif", "heic", "heif", "jxl")
    val ZIP = setOf("zip", "cbz")
    val RAR = setOf("rar", "cbr")
    val SEVEN_ZIP = setOf("7z", "cb7")
    val TAR = setOf("tar", "cbt")
    val ARCHIVE = ZIP + RAR + SEVEN_ZIP + TAR
    val PDF = setOf("pdf")

    fun ext(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i < 0) "" else name.substring(i + 1).lowercase()
    }

    fun typeOf(name: String): EntryType {
        val e = ext(name)
        return when {
            e in IMAGE -> EntryType.IMAGE
            e in ARCHIVE -> EntryType.ARCHIVE
            e in PDF -> EntryType.PDF
            else -> EntryType.OTHER
        }
    }

    fun isImageName(name: String): Boolean = ext(name) in IMAGE

    fun isArchiveName(name: String): Boolean = ext(name) in ARCHIVE

    fun isOpenable(name: String): Boolean = typeOf(name) != EntryType.OTHER
}
