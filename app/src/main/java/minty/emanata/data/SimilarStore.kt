package minty.emanata.data

import minty.emanata.util.PageHash
import minty.emanata.util.SimilarBooks
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Books' [SimilarStore.Entry] by [BookId], so each book's pages are read for the similar-books
 * search once, however often it runs or wherever the book moves. One text [file], a line per
 * book appended as found (a forgotten one's line says so) and read whole on first use, then
 * held in memory until [release]; past [max] books it is rewritten with the [max] / 2 found
 * last. A cache: made again when lost.
 */
class SimilarStore(private val file: File, private val max: Int = MAX) {

    /**
     * What the search keeps of a book: its [print], the [width] x [height] of a page in its
     * middle (0 when unknown; in points for a vector book, [SimilarBooks.Print.vector]), its
     * [container] (file extension) and the [image] format of its pages (their extension;
     * empty when unknown).
     */
    data class Entry(val print: SimilarBooks.Print, val width: Int, val height: Int, val container: String, val image: String)

    private var known: LinkedHashMap<Long, Entry>? = null

    @Synchronized
    fun get(id: BookId): Entry? = entries()[id.value]

    /** Keeps [entry] for book [id]; while nothing is held in memory ([release]), only appended, unread. */
    @Synchronized
    fun put(id: BookId, entry: Entry) {
        if (known == null && file.isFile) {
            runCatching { append("${id.hex}\t${encode(entry)}\n") }
            return
        }
        val all = entries()
        all.remove(id.value)
        all[id.value] = entry
        runCatching {
            if (all.size > max) {
                val kept = all.entries.toList().takeLast(max / 2)
                all.clear()
                kept.forEach { all[it.key] = it.value }
                write()
            } else {
                append("${id.hex}\t${encode(entry)}\n")
            }
        }
    }

    /** Forgets book [id], as when it is deleted; while nothing is held in memory ([release]), by a line saying so, unread. */
    @Synchronized
    fun forget(id: BookId) {
        val all = known
        val gone = if (all == null) file.isFile else all.remove(id.value) != null
        if (gone) runCatching { append("${id.hex}\t$GONE\n") }
    }

    /** Lets go of the books held in memory; the next use reads them again. */
    @Synchronized
    fun release() {
        known = null
    }

    /** Forgets every book. */
    @Synchronized
    fun clear() {
        known = LinkedHashMap()
        file.delete()
    }

    private fun append(line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line)
    }

    private fun entries(): LinkedHashMap<Long, Entry> = known ?: LinkedHashMap<Long, Entry>().also { map ->
        runCatching {
            if (file.isFile) file.forEachLine { line ->
                val tab = line.indexOf('\t')
                val id = BookId.parse(line.substring(0, tab.coerceAtLeast(0))) ?: return@forEachLine
                map.remove(id.value)
                decode(line.substring(tab + 1))?.let { map[id.value] = it }
            }
        }
        known = map
    }

    private fun write() {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.bufferedWriter().use { w -> known?.forEach { (id, entry) -> w.write("${BookId(id).hex}\t${encode(entry)}\n") } }
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** Books kept before the file is rewritten. */
        const val MAX = 32_768

        /** The line of a forgotten book. */
        private const val GONE = "-"

        private const val FILE = "similar_books.txt"

        /** The store's file under [cacheDir]. */
        fun fileIn(cacheDir: File) = File(cacheDir, FILE)

        @Volatile
        private var shared: SimilarStore? = null

        /** The one store under [cacheDir] every caller shares (a new one when that changes, as between tests). */
        @Synchronized
        fun of(cacheDir: File): SimilarStore {
            val file = fileIn(cacheDir)
            shared?.takeIf { it.file == file }?.let { return it }
            return SimilarStore(file).also { shared = it }
        }

        /** `pages, width, height, container, image, vector (1 or 0)` and the places' hashes, tab-separated; places `;`-separated, their pages `,`-separated, `-` for an unreadable one. */
        internal fun encode(e: Entry): String =
            listOf(e.print.pages, e.width, e.height, clean(e.container), clean(e.image), if (e.print.vector) 1 else 0).joinToString("\t") + "\t" +
                e.print.slots.joinToString(";") { slot -> slot.joinToString(",") { it?.encode() ?: "-" } }

        /** The entry [encode] wrote; null for a forgotten book or anything unreadable. */
        internal fun decode(text: String): Entry? {
            val parts = text.split('\t')
            if (parts.size != 7) return null
            val pages = parts[0].toIntOrNull() ?: return null
            val vector = when (parts[5]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            val slots = parts[6].split(';').map { slot ->
                if (slot.isEmpty()) emptyList() else slot.split(',').map { if (it == "-") null else PageHash.decode(it) ?: return null }
            }
            if (slots.size != SimilarBooks.SLOTS) return null
            return Entry(SimilarBooks.Print(pages, slots, vector), parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0, parts[3], parts[4])
        }

        /** [value] without the separators lines use. */
        private fun clean(value: String) = value.filterNot { it == '\t' || it == '\n' || it == '\r' || it == ';' || it == ',' }
    }
}
