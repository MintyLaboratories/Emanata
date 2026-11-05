package com.infinity.comicreader.data

import com.infinity.comicreader.util.Hashes
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Covers' [CoverFocus], kept across sessions in one text [file]: a line per cover, its key's
 * hash and the focus, appended as found; read whole on first use. Past [max] covers the file
 * is rewritten with the [max] / 2 found last.
 */
class FocusStore(private val file: File, private val max: Int = MAX) {
    private var known: LinkedHashMap<String, String>? = null

    @Synchronized
    fun get(key: String): CoverFocus? = CoverFocus.decode(entries()[hash(key)])

    @Synchronized
    fun put(key: String, focus: CoverFocus) {
        val all = entries()
        val h = hash(key)
        val line = focus.encode()
        all.remove(h)
        all[h] = line
        runCatching {
            if (all.size > max) {
                val kept = all.entries.toList().takeLast(max / 2)
                all.clear()
                kept.forEach { all[it.key] = it.value }
                write()
            } else {
                file.parentFile?.mkdirs()
                file.appendText("$h\t$line\n")
            }
        }
    }

    /** Forgets every cover's focus. */
    @Synchronized
    fun clear() {
        known = LinkedHashMap()
        file.delete()
    }

    private fun entries(): LinkedHashMap<String, String> = known ?: LinkedHashMap<String, String>().also { map ->
        runCatching {
            if (file.isFile) file.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val h = line.substring(0, tab)
                    map.remove(h)
                    map[h] = line.substring(tab + 1)
                }
            }
        }
        known = map
    }

    private fun write() {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.bufferedWriter().use { w -> known?.forEach { (h, line) -> w.write("$h\t$line\n") } }
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun hash(key: String): String = Hashes.sha256Hex(key).take(32)

    companion object {
        /** Covers kept before the file is rewritten. */
        const val MAX = 8192
    }
}
