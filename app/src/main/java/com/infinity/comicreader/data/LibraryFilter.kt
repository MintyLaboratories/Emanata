package com.infinity.comicreader.data

/** The direction a book reads in. */
enum class ReadingDirection(val key: String) {
    LTR("ltr"),
    RTL("rtl"),
    TTB("ttb");

    companion object {
        fun of(key: String?): ReadingDirection? = entries.firstOrNull { it.key == key }
    }
}

/** How far a book (or a folder's books) has been read. */
enum class ReadStatus(val key: String) {
    UNREAD("unread"),
    IN_PROGRESS("progress"),
    FINISHED("finished");

    companion object {
        fun of(key: String?): ReadStatus? = entries.firstOrNull { it.key == key }

        /**
         * A book: finished when marked read, in progress once opened. A folder, by
         * the books directly in it: finished when all are read, in progress once
         * any is opened, unknown (null) when it holds none.
         */
        fun of(entry: BookEntry): ReadStatus? = if (entry.isDir) {
            when {
                entry.bookCount <= 0 -> null
                entry.readCount >= entry.bookCount -> FINISHED
                entry.readCount > 0 || entry.begunCount > 0 -> IN_PROGRESS
                else -> UNREAD
            }
        } else {
            when {
                entry.read -> FINISHED
                entry.begun -> IN_PROGRESS
                else -> UNREAD
            }
        }
    }
}

/**
 * One tab's filter: entries of [types], reading in [direction], with a status in
 * [statuses], a year and a length within the bounds (inclusive). Empty sets and
 * null bounds let everything through; an entry without a status, year or length
 * fails a rule on it.
 */
data class LibraryFilter(
    val types: Set<EntryType> = emptySet(),
    val direction: ReadingDirection? = null,
    val statuses: Set<ReadStatus> = emptySet(),
    val minYear: Int? = null,
    val maxYear: Int? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null
) {
    /** The same filter with "everything" sets emptied and bounds put in order. */
    fun normalized(): LibraryFilter {
        val (y0, y1) = ordered(minYear, maxYear)
        val (l0, l1) = ordered(minLength, maxLength)
        return LibraryFilter(
            types = types.takeIf { !it.containsAll(TYPES) }.orEmpty() - EntryType.OTHER,
            direction = direction,
            statuses = statuses.takeIf { it.size < ReadStatus.entries.size }.orEmpty(),
            minYear = y0, maxYear = y1, minLength = l0, maxLength = l1
        )
    }

    /** True when it hides anything. */
    val isActive: Boolean get() = this.normalized() != NONE

    /** Values it reads beyond what an entry holds. */
    val keys: Set<SortKey>
        get() = setOfNotNull(
            SortKey.YEAR.takeIf { minYear != null || maxYear != null },
            SortKey.LENGTH.takeIf { minLength != null || maxLength != null },
            SortKey.DIRECTION.takeIf { direction != null }
        )

    /**
     * True when [entry] passes. While [browsing], folders face only the type rule,
     * so the books below them stay reachable.
     */
    fun accepts(entry: BookEntry, values: SortValues, browsing: Boolean = false): Boolean {
        val f = normalized()
        if (f.types.isNotEmpty() && entry.type !in f.types) return false
        if (browsing && entry.isDir) return true
        if (f.statuses.isNotEmpty() && ReadStatus.of(entry) !in f.statuses) return false
        if (f.direction != null && values.of(entry, SortKey.DIRECTION) != f.direction.ordinal.toLong()) return false
        if (!within(values.of(entry, SortKey.YEAR), f.minYear, f.maxYear)) return false
        if (!within(values.of(entry, SortKey.LENGTH), f.minLength, f.maxLength)) return false
        return true
    }

    /** `types=archive,pdf;dir=rtl;status=unread;year=2000-2010;length=-200`; empty when inactive. */
    fun encode(): String {
        val f = normalized()
        val parts = ArrayList<String>()
        if (f.types.isNotEmpty()) parts += "types=" + f.types.sortedBy { it.ordinal }.joinToString(",") { it.name.lowercase() }
        f.direction?.let { parts += "dir=" + it.key }
        if (f.statuses.isNotEmpty()) parts += "status=" + f.statuses.sortedBy { it.ordinal }.joinToString(",") { it.key }
        if (f.minYear != null || f.maxYear != null) parts += "year=" + range(f.minYear, f.maxYear)
        if (f.minLength != null || f.maxLength != null) parts += "length=" + range(f.minLength, f.maxLength)
        return parts.joinToString(";")
    }

    companion object {
        val NONE = LibraryFilter()
        private val TYPES = EntryType.entries.toSet() - EntryType.OTHER

        /** Reads [encode]'s form; unknown parts are ignored. */
        fun decode(value: String?): LibraryFilter {
            if (value.isNullOrBlank()) return NONE
            var f = NONE
            for (part in value.split(';')) {
                val name = part.substringBefore('=')
                val v = part.substringAfter('=', "")
                f = when (name) {
                    "types" -> f.copy(types = v.split(',').mapNotNull { t -> TYPES.firstOrNull { it.name.equals(t, true) } }.toSet())
                    "dir" -> f.copy(direction = ReadingDirection.of(v))
                    "status" -> f.copy(statuses = v.split(',').mapNotNull { ReadStatus.of(it) }.toSet())
                    "year" -> parseRange(v).let { (a, b) -> f.copy(minYear = a, maxYear = b) }
                    "length" -> parseRange(v).let { (a, b) -> f.copy(minLength = a, maxLength = b) }
                    else -> f
                }
            }
            return f.normalized()
        }

        private fun ordered(a: Int?, b: Int?): Pair<Int?, Int?> =
            if (a != null && b != null && a > b) b to a else a to b

        private fun within(value: Long?, min: Int?, max: Int?): Boolean {
            if (min == null && max == null) return true
            if (value == null) return false
            return (min == null || value >= min) && (max == null || value <= max)
        }

        private fun range(min: Int?, max: Int?) = "${min ?: ""}-${max ?: ""}"

        private fun parseRange(v: String): Pair<Int?, Int?> =
            v.substringBefore('-').toIntOrNull() to v.substringAfter('-', "").toIntOrNull()
    }
}
