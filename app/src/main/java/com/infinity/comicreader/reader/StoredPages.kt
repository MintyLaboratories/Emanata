package com.infinity.comicreader.reader

import java.util.BitSet

/**
 * The page sizes ([PageSizes]), seams ([PageSeams]) and pages seen stored for book
 * [bookId], as [source] shows its pages. Sizes, pages seen, and seams between pages next
 * to each other in the book, are kept for the whole book, so hiding pages or showing them
 * again reads none of them anew; seams across a hidden page are kept for the pages shown
 * ([HiddenPages.pagesId]). Blocking.
 */
class StoredPages(
    private val sizes: PageSizes,
    private val seams: PageSeams,
    private val bookId: String,
    source: PageSource
) {
    private val hiding = source as? HidingSource

    /** The names of the pages [source] shows. */
    val names: List<String?> = namesOf(source)

    private val bookNames: List<String?> = hiding?.let { namesOf(it.inner) } ?: names
    private val shownId = HiddenPages.pagesId(bookId, source)

    private fun book(i: Int) = hiding?.bookIndex(i) ?: i

    /** True when shown pages [i] and `i + 1` are next to each other in the book. */
    private fun adjacent(i: Int) = book(i + 1) == book(i) + 1

    /** The sizes stored for the pages shown, null where unknown. */
    fun readSizes(): Array<IntArray?> {
        val all = sizes.read(bookId, bookNames) ?: return arrayOfNulls(names.size)
        return Array(names.size) { all.getOrNull(book(it)) }
    }

    /** Stores [found], the sizes of the pages shown, along with those kept for the rest of the book. */
    fun writeSizes(found: Array<IntArray?>) {
        if (hiding == null) return sizes.write(bookId, names, found)
        val all = sizes.read(bookId, bookNames) ?: arrayOfNulls(bookNames.size)
        for (i in found.indices) found[i]?.let { all[book(i)] = it }
        sizes.write(bookId, bookNames, all)
    }

    /** The seams stored after each page shown. */
    fun readSeams(): PageSeams.Found {
        if (hiding == null) return seams.read(bookId, names) ?: PageSeams.Found(names.size)
        val out = PageSeams.Found(names.size)
        val all = seams.read(bookId, bookNames)
        val own = seams.read(shownId, names)
        val allLtr = all?.of(rightToLeft = false)
        val allRtl = all?.of(rightToLeft = true)
        val ownLtr = own?.of(rightToLeft = false)
        val ownRtl = own?.of(rightToLeft = true)
        for (i in 0 until names.size - 1) {
            val b = book(i)
            val ltr = (if (adjacent(i)) allLtr?.getOrNull(b) else null) ?: ownLtr?.getOrNull(i)
            val rtl = (if (adjacent(i)) allRtl?.getOrNull(b) else null) ?: ownRtl?.getOrNull(i)
            if (ltr != null && rtl != null) out.set(i, ltr, rtl)
        }
        return out
    }

    /** Stores [found], the seams after the pages shown: those of pages next to each other for the whole book. */
    fun writeSeams(found: PageSeams.Found) {
        if (hiding == null) return seams.write(bookId, names, found)
        val all = seams.read(bookId, bookNames) ?: PageSeams.Found(bookNames.size)
        val ltr = found.of(rightToLeft = false)
        val rtl = found.of(rightToLeft = true)
        var across = false
        for (i in ltr.indices) {
            val l = ltr[i] ?: continue
            val r = rtl[i] ?: continue
            if (adjacent(i)) all.set(book(i), l, r) else across = true
        }
        seams.write(bookId, bookNames, all)
        if (across) seams.write(shownId, names, found)
    }

    /** The pages shown that the reader has had on screen, kept for the whole book ([PageSeams.readSeen]). */
    fun readSeen(): BitSet {
        val all = seams.readSeen(bookId, bookNames)
        if (hiding == null) return all
        return BitSet(names.size).also { out -> for (i in names.indices) if (all.get(book(i))) out.set(i) }
    }

    /** Adds [seen], pages shown, to those kept for the whole book; writes only when any is new. */
    fun writeSeen(seen: BitSet) {
        val all = seams.readSeen(bookId, bookNames)
        val before = all.cardinality()
        var i = seen.nextSetBit(0)
        while (i in names.indices) {
            all.set(book(i))
            i = seen.nextSetBit(i + 1)
        }
        if (all.cardinality() != before) seams.writeSeen(bookId, bookNames, all)
    }

    private companion object {
        fun namesOf(source: PageSource): List<String?> = List(source.pageCount) { runCatching { source.pageName(it) }.getOrNull() }
    }
}
