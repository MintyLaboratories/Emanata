package com.infinity.comicreader.data

/** The box covers are drawn in: [ratio] is width / height, 0 for the cell's own shape. */
enum class CoverShape(val key: String, val ratio: Float) {
    BOOK("2:3", 2f / 3f),
    WIDE("3:4", 3f / 4f),
    CELL("cell", 0f);

    companion object {
        fun of(key: String?): CoverShape = entries.firstOrNull { it.key == key } ?: BOOK
    }
}
