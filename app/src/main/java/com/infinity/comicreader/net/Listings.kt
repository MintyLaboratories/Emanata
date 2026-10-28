package com.infinity.comicreader.net

import com.infinity.comicreader.util.Hashes
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Folder listings of shares as last found, kept in memory and, under [dir], on disk (one
 * file per folder, in a folder per share), so a folder browsed before shows at once,
 * even after a restart, while it is listed again. A listing is written only when it
 * changed. Never touches the network.
 */
class Listings(private val dir: File?, private val maxFolders: Int = MAX_IN_MEMORY) {
    private val memory = object : LinkedHashMap<String, List<RemoteStat>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RemoteStat>>) = size > maxFolders
    }

    /** The entries of folder [path] as last listed; null when it never was, or what was kept cannot be read. */
    fun get(path: String): List<RemoteStat>? {
        synchronized(memory) { memory[path] }?.let { return it }
        val found = read(path) ?: return null
        synchronized(memory) { memory[path] = found }
        return found
    }

    /** Keeps [entries] as the listing of folder [path]; true when they differ from those kept. */
    fun put(path: String, entries: List<RemoteStat>): Boolean {
        val before = get(path)
        if (before != null && same(before, entries)) return false
        synchronized(memory) { memory[path] = entries }
        write(path, entries)
        return true
    }

    /** Forgets the listings of share [id], on disk too. */
    fun forget(id: String) {
        val root = Remotes.rootOf(id)
        synchronized(memory) { memory.keys.removeAll { it == root || it.startsWith("$root/") } }
        dir?.let { File(it, id).deleteRecursively() }
    }

    /** Forgets the listing of folder [path] and, in memory, those of the folders inside it. */
    fun drop(path: String) {
        synchronized(memory) { memory.keys.removeAll { it == path || it.startsWith("$path/") } }
        fileOf(path)?.delete()
    }

    /** Forgets every listing kept in memory; those on disk stay. */
    fun clearMemory() {
        synchronized(memory) { memory.clear() }
    }

    /** Forgets every listing, on disk too. */
    fun clear() {
        clearMemory()
        dir?.deleteRecursively()
    }

    private fun fileOf(path: String): File? {
        val id = Remotes.idOf(path) ?: return null
        val base = dir ?: return null
        return File(File(base, id), Hashes.sha256Hex(path).take(NAME_CHARS))
    }

    private fun read(path: String): List<RemoteStat>? {
        val file = fileOf(path)?.takeIf { it.isFile } ?: return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != VERSION || input.readUTF() != path) return null
                List(input.readInt()) {
                    RemoteStat(input.readUTF(), input.readBoolean(), input.readLong(), input.readLong(), input.readLong())
                }
            }
        }.getOrNull()
    }

    private fun write(path: String, entries: List<RemoteStat>) {
        val file = fileOf(path) ?: return
        val dir = file.parentFile ?: return
        dir.mkdirs()
        // Its own part file: two threads may store the same folder at once.
        val part = runCatching { File.createTempFile(file.name, ".part", dir) }.getOrNull() ?: return
        runCatching {
            DataOutputStream(part.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(path)
                out.writeInt(entries.size)
                for (s in entries) {
                    out.writeUTF(s.name)
                    out.writeBoolean(s.isDirectory)
                    out.writeLong(s.size)
                    out.writeLong(s.modified)
                    out.writeLong(s.created)
                }
            }
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        }
        part.delete()
    }

    companion object {
        const val DIR = "listings"
        private const val VERSION = 1
        private const val NAME_CHARS = 32
        const val MAX_IN_MEMORY = 1024

        /** How much the listings on disk may take, least recently written going first. */
        const val MAX_DISK_BYTES = 16L * 1024 * 1024

        fun dirIn(cacheDir: File) = File(cacheDir, DIR)

        /** True when [a] and [b] hold the same entries, in any order. */
        fun same(a: List<RemoteStat>, b: List<RemoteStat>): Boolean =
            a.size == b.size && a.sortedBy { it.name } == b.sortedBy { it.name }

        /** Deletes the least recently written listings under [dir] until those left take at most [maxBytes]. */
        fun trim(dir: File, maxBytes: Long = MAX_DISK_BYTES) {
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            var total = files.sumOf { it.length() }
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                val size = f.length()
                if (f.delete()) total -= size
            }
        }
    }
}
