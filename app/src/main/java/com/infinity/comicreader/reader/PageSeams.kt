package com.infinity.comicreader.reader

import com.infinity.comicreader.util.Hashes
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.BitSet
import kotlin.math.roundToInt

/**
 * The seams between a book's pages ([SpreadSeams]), read left to right and right to
 * left, checked in the background ([check]) and kept on disk under [dir]: one small file
 * per book, named like [PageSizes] by the book's identity and its page names, and beside
 * it one of the pages the reader has had on screen ([readSeen]).
 */
class PageSeams(val dir: File) {

    /** The seams of a book of [count] pages after each page but the last, both ways; null where not checked. */
    class Found(val count: Int) {
        private val ltr = arrayOfNulls<SpreadSeams.Verdict>(maxOf(count - 1, 0))
        private val rtl = arrayOfNulls<SpreadSeams.Verdict>(maxOf(count - 1, 0))

        /** The seam after each page read [rightToLeft] or not, null where not checked. */
        fun of(rightToLeft: Boolean): List<SpreadSeams.Verdict?> = (if (rightToLeft) rtl else ltr).toList()

        /** True when the seam after page [i] was checked. */
        fun checked(i: Int): Boolean = ltr.getOrNull(i) != null

        /** Keeps the seam after page [i] read left to right ([leftToRight]) and right to left ([rightToLeft]). */
        fun set(i: Int, leftToRight: SpreadSeams.Verdict, rightToLeft: SpreadSeams.Verdict) {
            ltr[i] = leftToRight
            rtl[i] = rightToLeft
        }

        /** True when any seam was checked. */
        val anyChecked: Boolean get() = ltr.any { it != null }

        /** True when any seam runs on, either way. */
        val anyMatch: Boolean get() = ltr.any { it?.match == true } || rtl.any { it?.match == true }

        fun copy(): Found = Found(count).also { c ->
            for (i in ltr.indices) {
                val a = ltr[i] ?: continue
                c.set(i, a, rtl[i] ?: SpreadSeams.Verdict.UNKNOWN)
            }
        }
    }

    /** The seams stored for book [bookId] with pages [names]; null when none are. */
    fun read(bookId: String, names: List<String?>): Found? {
        val file = fileOf(bookId, names)
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != VERSION) return null
                val count = input.readInt()
                if (count != names.size) return null
                Found(count).also { found ->
                    for (i in 0 until maxOf(count - 1, 0)) {
                        val ltr = verdictOf(input.readByte(), input.readByte())
                        val rtl = verdictOf(input.readByte(), input.readByte())
                        if (ltr != null && rtl != null) found.set(i, ltr, rtl)
                    }
                }
            }
        }.getOrNull()
    }

    /** Stores [found] of book [bookId] with pages [names]; a file appears whole or not at all. */
    fun write(bookId: String, names: List<String?>, found: Found) {
        val file = fileOf(bookId, names)
        val part = File(file.path + ".part")
        try {
            dir.mkdirs()
            DataOutputStream(part.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeInt(found.count)
                val ltr = found.of(rightToLeft = false)
                val rtl = found.of(rightToLeft = true)
                for (i in ltr.indices) {
                    write(out, ltr[i])
                    write(out, rtl[i])
                }
            }
            if (!part.renameTo(file)) throw IOException("Could not store ${file.name}")
        } catch (e: IOException) {
            part.delete()
        }
    }

    /**
     * The pages of book [bookId] with pages [names] the reader has had on screen, by index,
     * kept beside its seams in a file of their own: a lone seam match near one of them
     * settles its run ([Spreads.Options.visited]). Empty when none are kept.
     */
    fun readSeen(bookId: String, names: List<String?>): BitSet {
        val file = seenFileOf(bookId, names)
        if (!file.isFile) return BitSet()
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != SEEN_VERSION || input.readInt() != names.size) return BitSet()
                val words = LongArray(input.readInt().coerceIn(0, (names.size + 63) / 64)) { input.readLong() }
                BitSet.valueOf(words).also { it.clear(names.size, maxOf(names.size, it.length())) }
            }
        }.getOrDefault(BitSet())
    }

    /** Stores [seen], pages of book [bookId] with pages [names], as [readSeen] reads them; a file appears whole or not at all. */
    fun writeSeen(bookId: String, names: List<String?>, seen: BitSet) {
        val file = seenFileOf(bookId, names)
        val part = File(file.path + ".part")
        try {
            dir.mkdirs()
            val words = seen.get(0, names.size).toLongArray()
            DataOutputStream(part.outputStream().buffered()).use { out ->
                out.writeInt(SEEN_VERSION)
                out.writeInt(names.size)
                out.writeInt(words.size)
                for (w in words) out.writeLong(w)
            }
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            part.delete()
        }
    }

    private fun write(out: DataOutputStream, v: SpreadSeams.Verdict?) {
        out.writeByte(v?.seam?.let { it.ordinal + 1 } ?: 0)
        out.writeByte(((v?.confidence ?: 0.0) * 255).roundToInt().coerceIn(0, 255))
    }

    private fun verdictOf(seam: Byte, confidence: Byte): SpreadSeams.Verdict? {
        val kind = SpreadSeams.Seam.entries.getOrNull(seam - 1) ?: return null
        return SpreadSeams.Verdict(kind, (confidence.toInt() and 0xFF) / 255.0)
    }

    private fun fileOf(bookId: String, names: List<String?>) = File(dir, keyOf(bookId, names) + ".bin")

    private fun seenFileOf(bookId: String, names: List<String?>) = File(dir, keyOf(bookId, names) + ".seen")

    private fun keyOf(bookId: String, names: List<String?>) =
        Hashes.sha256Hex(bookId + "\u0000" + names.joinToString("\u0000") { it.orEmpty() })

    companion object {
        /** Of the file format and of [SpreadSeams.compare]: seams kept under another are checked again. */
        private const val VERSION = 2

        /** Of the pages-seen file format. */
        private const val SEEN_VERSION = 1

        /** Where seams are kept under [cacheDir]. */
        fun dirIn(cacheDir: File) = File(cacheDir, "page-seams")

        /**
         * The pages whose seam with the next page [found] lacks, where [analysis] lets the two
         * share a screen at all (neither wider than tall, in a book of portrait pages): nearest
         * page [from] first ([PageSizes.outward]); beyond [window] pages of it, when given,
         * only those whose sides and the next page's are [cheap] to read.
         */
        fun unchecked(
            analysis: Spreads.Analysis,
            found: Found,
            from: Int,
            window: Int? = null,
            cheap: (Int) -> Boolean = { false }
        ): List<Int> {
            if (analysis.landscape) return emptyList()
            val last = minOf(analysis.size, found.count) - 1
            return PageSizes.outward(maxOf(last, 0), from).filter { i ->
                (window == null || i in from - window..from + window || (cheap(i) && cheap(i + 1))) && !found.checked(i) &&
                    analysis.pages[i].aspect <= 1.0 && analysis.pages[i + 1].aspect <= 1.0
            }
        }

        /**
         * Checks the seams after [pages] into [found], reading each page's sides from [source]
         * once for both of its seams, until [canceled]; a page whose sides cannot be read leaves
         * its seams unknown. Returns false when canceled first. Blocking.
         */
        fun check(source: PageSource, pages: List<Int>, found: Found, canceled: () -> Boolean = { false }): Boolean {
            val kept = object : LinkedHashMap<Int, SpreadSeams.Sides?>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SpreadSeams.Sides?>?) = size > KEPT_SIDES
            }
            fun sides(i: Int) = if (kept.containsKey(i)) kept[i] else runCatching { source.sides(i) }.getOrNull().also { kept[i] = it }
            for (i in pages) {
                if (canceled()) return false
                val a = sides(i)
                val b = if (canceled()) return false else sides(i + 1)
                if (a == null || b == null) {
                    found.set(i, SpreadSeams.Verdict.UNKNOWN, SpreadSeams.Verdict.UNKNOWN)
                } else {
                    found.set(i, SpreadSeams.verdict(a, b, rtl = false), SpreadSeams.verdict(a, b, rtl = true))
                }
            }
            return true
        }

        /** Pages whose sides [check] keeps while going on to their neighbors. */
        private const val KEPT_SIDES = 6
    }
}
