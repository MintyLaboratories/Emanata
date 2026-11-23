package com.infinity.comicreader.compact

import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.BookId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Books' data waiting to follow their rebuilt files, kept in [dir] from just before a new
 * file takes a book's place until the data has followed it: the app stopping in between
 * is made good when it starts again.
 */
class Handovers(private val dir: File) {
    /**
     * The data of book [old] at [book] to hand to [new], its rebuilt file at [target]; the
     * book's file [removed] or not; book page `i` is now page [moves]`[i]` of [pageCount];
     * the pages byte-identical in the new file, whose caches follow them ([same]), none when null.
     */
    class Entry(
        val old: BookId,
        val new: BookId,
        val book: String,
        val target: String,
        val removed: Boolean,
        val moves: IntArray,
        val pageCount: Int,
        val same: BookCaches.SamePages? = null,
        internal val file: File? = null
    )

    /** Writes [entry] to the device before returning it, as kept. */
    fun add(entry: Entry): Entry {
        dir.mkdirs()
        val json = JSONObject()
            .put("old", entry.old.hex)
            .put("new", entry.new.hex)
            .put("book", entry.book)
            .put("target", entry.target)
            .put("removed", entry.removed)
            .put("moves", JSONArray(entry.moves.toList()))
            .put("pageCount", entry.pageCount)
        entry.same?.let { same ->
            json.put(
                "same",
                JSONObject()
                    .put("pages", JSONArray(same.pages.toList()))
                    .put("oldNames", JSONArray(same.oldNames))
                    .put("newNames", JSONArray(same.newNames))
            )
        }
        val name = "${System.nanoTime()}$SUFFIX"
        val temp = File(dir, "$name.tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.toString().toByteArray())
            out.fd.sync()
        }
        val file = File(dir, name)
        if (!temp.renameTo(file)) throw java.io.IOException("Cannot keep ${file.name}")
        return Entry(entry.old, entry.new, entry.book, entry.target, entry.removed, entry.moves, entry.pageCount, entry.same, file)
    }

    fun remove(entry: Entry) {
        entry.file?.delete()
    }

    /** The entries kept, oldest first; unreadable ones are dropped. */
    fun entries(): List<Entry> {
        val files = dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().sortedBy { it.name.removeSuffix(SUFFIX).toLongOrNull() ?: 0L }
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        return files.mapNotNull { file ->
            val entry = runCatching {
                val o = JSONObject(file.readText())
                val moves = o.getJSONArray("moves")
                Entry(
                    BookId.parse(o.getString("old"))!!, BookId.parse(o.getString("new"))!!, o.getString("book"), o.getString("target"),
                    o.getBoolean("removed"), IntArray(moves.length()) { moves.getInt(it) }, o.getInt("pageCount"),
                    o.optJSONObject("same")?.let(::samePages), file
                )
            }.getOrNull()
            if (entry == null) file.delete()
            entry
        }
    }

    private companion object {
        const val SUFFIX = ".json"

        fun samePages(o: JSONObject): BookCaches.SamePages {
            val pages = o.getJSONArray("pages")
            return BookCaches.SamePages(IntArray(pages.length()) { pages.getInt(it) }, strings(o.getJSONArray("oldNames")), strings(o.getJSONArray("newNames")))
        }

        fun strings(a: JSONArray): List<String> = List(a.length()) { a.getString(it) }
    }
}
