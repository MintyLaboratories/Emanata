package com.infinity.comicreader.reader

import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.util.CacheFiles
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.BitSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * The seams between a book's pages ([SpreadSeams]), read left to right and right to
 * left, checked in the background ([check]) and kept on disk under [dir]: one small file
 * per book, named like [PageSizes] after the book's identity and its page names, and beside
 * it one of the pages the reader has had on screen ([readSeen]). Bounded like [PageSizes]:
 * each read marks its file used, the least recently used going first ([trim]).
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
        }.getOrNull()?.also { CacheFiles.touch(file) }
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
        wrote()
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
        wrote()
    }

    /**
     * Stores under book [to] with pages [toNames] the seams and pages seen kept for book
     * [from] with pages [fromNames], page `i` as page [map]`[i]` (none where -1), over what
     * [to] has there: a seam only where both its pages map and stay next to each other.
     * With [move], [from]'s go.
     */
    fun carry(from: String, fromNames: List<String?>, to: String, toNames: List<String?>, map: IntArray, move: Boolean) {
        fun at(i: Int) = map.getOrElse(i) { -1 }.takeIf { it in toNames.indices } ?: -1
        read(from, fromNames)?.let { old ->
            val out = read(to, toNames) ?: Found(toNames.size)
            val ltr = old.of(rightToLeft = false)
            val rtl = old.of(rightToLeft = true)
            var any = false
            for (i in ltr.indices) {
                val l = ltr[i] ?: continue
                val r = rtl[i] ?: continue
                val j = at(i)
                if (j < 0 || at(i + 1) != j + 1) continue
                out.set(j, l, r)
                any = true
            }
            if (any) write(to, toNames, out)
        }
        val seen = readSeen(from, fromNames)
        if (!seen.isEmpty) {
            val out = readSeen(to, toNames)
            val before = out.cardinality()
            var i = seen.nextSetBit(0)
            while (i >= 0) {
                at(i).takeIf { it >= 0 }?.let(out::set)
                i = seen.nextSetBit(i + 1)
            }
            if (out.cardinality() != before) writeSeen(to, toNames, out)
        }
        if (move) {
            fileOf(from, fromNames).delete()
            seenFileOf(from, fromNames).delete()
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
        CacheFiles.nameOf(bookId, bookId + "\u0000" + names.joinToString("\u0000") { it.orEmpty() })

    /** Counts a write, trimming the store every [TRIM_EVERY] of them. */
    private fun wrote() {
        if (WRITES.incrementAndGet() % TRIM_EVERY == 0) trim(dir)
    }

    companion object {
        /** Of the file format and of [SpreadSeams.compare]: seams kept under another are checked again. */
        private const val VERSION = 2

        /** Of the pages-seen file format. */
        private const val SEEN_VERSION = 1

        /** Upper bound of the seams and pages seen kept on disk: some four thousand books. */
        const val MAX_BYTES = 32L * 1024 * 1024

        /** Writes between trims while the app runs; the store is trimmed at startup too. */
        private const val TRIM_EVERY = 64

        /** Seams and pages seen written since the app started, by every store. */
        private val WRITES = AtomicInteger()

        /** Where seams are kept under [cacheDir], by book ID. */
        fun dirIn(cacheDir: File) = File(cacheDir, "page-seams-by-id")

        /** Keeps [dir] under [maxBytes], the books least recently read going first. */
        fun trim(dir: File, maxBytes: Long = MAX_BYTES) = CacheFiles.trim(dir, maxBytes)

        /** Deletes the seams and pages seen kept for book [id] in [dir]. */
        fun forget(dir: File, id: BookId) = CacheFiles.forget(dir, id.hex)

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
         * its seams unknown. Returns false when canceled first. Once [enough] says so, before
         * a page is read, it ends early as finished, the seams left unchecked for later. Blocking.
         */
        fun check(
            source: PageSource,
            pages: List<Int>,
            found: Found,
            enough: () -> Boolean = { false },
            canceled: () -> Boolean = { false }
        ): Boolean {
            val kept = object : LinkedHashMap<Int, SpreadSeams.Sides?>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SpreadSeams.Sides?>?) = size > KEPT_SIDES
            }
            fun sides(i: Int) = if (kept.containsKey(i)) kept[i] else runCatching { source.sides(i) }.getOrNull().also { kept[i] = it }
            for (i in pages) {
                if (canceled()) return false
                if (!kept.containsKey(i) && enough()) return true
                val a = sides(i)
                if (canceled()) return false
                if (!kept.containsKey(i + 1) && enough()) return true
                val b = sides(i + 1)
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
