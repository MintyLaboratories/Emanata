package com.infinity.comicreader.files

import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.Remotes
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/**
 * Copies and moves files and folders, within a [FileSpace] or between two. Nothing
 * half-written is ever left under the final name: a copy is written under a hidden
 * side name in the target folder ([sideName]) and renamed into place once complete,
 * and a failed or canceled one is deleted. Every file copied is checked for its size;
 * a move that copies (between spaces, or between the device's drives) also reads each
 * copy back and compares its CRC-32 with the source's before the source is deleted: only what was copied, so whatever came into a
 * folder meanwhile stays, and the folder with it. A move within one space is a rename.
 * Links (symbolic links, junctions) are left out: what they point to may be anywhere,
 * even around the folder itself. [progress] hears bytes done of the total, reading back
 * included; [alive] going false cancels at the next piece.
 */
class Transfer(
    private val spaceOf: (String) -> FileSpace = FileSpaces::of,
    private val alive: () -> Boolean = { true },
    private val progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    private val stamp: () -> String = { System.currentTimeMillis().toString(36) },
    /** Waits between tries to delete what a failure left ([discard]). */
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) {
    /** Where the item ended up; [sourceLeft] when a move copied it but could not delete the source. */
    data class Result(val path: String, val sourceLeft: Boolean = false)

    private var done = 0L
    private var total = 0L

    /** The source's files and folders copied so far, folders before what they hold. */
    private val copied = ArrayList<String>()

    /**
     * Copies, or moves when [move], [source] into folder [targetDir] as [name]; what is
     * there by that name is replaced when [replace], else fails the transfer.
     */
    fun run(source: String, targetDir: String, name: String, move: Boolean, replace: Boolean): Result {
        val from = spaceOf(source)
        val to = spaceOf(targetDir)
        val target = child(targetDir, name)
        require(target != source) { "Onto itself: $source" }
        require(!isWithin(targetDir, source)) { "Into itself: $source" }
        val node = from.stat(source) ?: throw FileNotFoundException(source)
        if (node.isLink) throw IOException("A link, not copied: $source")
        val existing = to.stat(target)
        if (existing != null && !replace) throw IOException("Already there: $target")
        if (move && from.sameAs(to)) renamed(from, source, targetDir, name, existing != null)?.let { return it }

        total = sizeOf(from, source, node) * if (move) 2 else 1
        done = 0
        progress(0, total)
        val part = sideName(to, targetDir, name, PART)
        try {
            copyTree(from, source, node, to, part, verify = move)
            checkAlive()
        } catch (e: Throwable) {
            discard(to, part)
            throw e
        }
        commit(to, part, targetDir, name, existing != null)
        if (!move) return Result(target)
        return Result(target, sourceLeft = !removeCopied(from))
    }

    /** Deletes what was copied from the source, deepest first, each folder once emptied; false when anything is left. */
    private fun removeCopied(space: FileSpace): Boolean {
        var all = true
        for (path in copied.asReversed()) {
            try {
                space.remove(path)
            } catch (e: IOException) {
                all = false
            }
        }
        return all
    }

    /** Moves by renaming, setting aside what it replaces until it is in place; null when the space cannot. */
    private fun renamed(space: FileSpace, source: String, targetDir: String, name: String, replace: Boolean): Result? {
        val target = child(targetDir, name)
        if (!replace) return if (space.rename(source, target)) Result(target) else null
        val old = sideName(space, targetDir, name, OLD)
        if (!space.rename(target, old)) return null
        val moved = try {
            space.rename(source, target)
        } catch (e: Throwable) {
            runCatching { space.rename(old, target) }
            throw e
        }
        if (!moved) {
            space.rename(old, target)
            return null
        }
        discard(space, old)
        return Result(target)
    }

    /** Renames the complete copy [part] to [name], setting aside what it replaces until then. */
    private fun commit(space: FileSpace, part: String, targetDir: String, name: String, replace: Boolean) {
        val target = child(targetDir, name)
        val old = if (replace) sideName(space, targetDir, name, OLD) else null
        try {
            if (old != null) check(space.rename(target, old)) { "Could not set $target aside" }
            try {
                check(space.rename(part, target)) { "Could not rename $part" }
            } catch (e: Throwable) {
                if (old != null) runCatching { space.rename(old, target) }
                throw e
            }
        } catch (e: Throwable) {
            discard(space, part)
            throw e
        }
        if (old != null) discard(space, old)
    }

    /**
     * Deletes [path], a copy left by a failure or set aside, trying again after each of
     * [DISCARD_WAITS_MS]: the failure may have been the share's, which then counts as
     * unreachable for a while. Never fails: what is left is swept later.
     */
    private fun discard(space: FileSpace, path: String) {
        for (wait in DISCARD_WAITS_MS) {
            try {
                if (wait > 0) pause(wait)
                space.discard(path)
                return
            } catch (e: RemoteException) {
                if (e.reason == RemoteException.Reason.MISSING) return
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                // Tried again.
            }
        }
    }

    private fun sizeOf(space: FileSpace, path: String, node: Node): Long {
        checkAlive()
        if (node.isLink) return 0
        if (!node.isDirectory) return node.size.coerceAtLeast(0)
        return space.list(path).sumOf { sizeOf(space, child(path, it.name), it) }
    }

    private fun copyTree(from: FileSpace, source: String, node: Node, to: FileSpace, target: String, verify: Boolean) {
        checkAlive()
        if (node.isLink) return
        if (!node.isDirectory) {
            copyFile(from, source, node, to, target, verify)
            copied += source
            return
        }
        to.mkdir(target)
        copied += source
        for (c in from.list(source).sortedBy { it.name }) copyTree(from, child(source, c.name), c, to, child(target, c.name), verify)
    }

    private fun copyFile(from: FileSpace, source: String, node: Node, to: FileSpace, target: String, verify: Boolean) {
        val base = done
        // A copy a move deletes the source for is read back, so never made by a server unseen.
        if (!verify && from.sameAs(to) && to.copy(source, target) { n -> checkAlive(); report(base + n) }) {
            done = base + node.size.coerceAtLeast(0)
            checkSize(to, target, node.size)
            return
        }
        val crc = CRC32()
        val read = Checked(from.open(source), crc)
        read.use { to.create(target, it, node.size, node.modified) }
        checkAlive()
        if (node.size >= 0 && read.count != node.size) throw IOException("Changed while copied: $source")
        checkSize(to, target, read.count)
        if (verify) {
            val back = CRC32()
            Checked(to.open(target), back).use { drain(it) }
            if (back.value != crc.value) throw IOException("Copy differs from its source: $target")
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(BUFFER)
        while (input.read(buffer) >= 0) Unit
    }

    private fun checkSize(space: FileSpace, path: String, size: Long) {
        val stored = space.stat(path) ?: throw IOException("Missing after copying: $path")
        if (size >= 0 && stored.size >= 0 && stored.size != size) throw IOException("Copy has ${stored.size} of $size bytes: $path")
    }

    private fun checkAlive() {
        if (!alive()) throw CancellationException("Canceled")
    }

    private fun report(at: Long) = progress(at, total)

    /** Reads through [crc], counting, reporting progress and stopping once canceled. */
    private inner class Checked(input: InputStream, private val crc: CRC32) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkAlive()
            val n = super.read(b, off, len)
            if (n > 0) {
                crc.update(b, off, n)
                count += n
                done += n
                report(done)
            }
            return n
        }

        /** Never skips: every byte is read, so it counts toward the check. */
        override fun skip(n: Long): Long = 0
    }

    /** A name for [name]'s copy under way or its setting aside in [dir], hidden, and free in [space]. */
    private fun sideName(space: FileSpace, dir: String, name: String, kind: String): String {
        val stem = ".$name.${stamp()}"
        var i = 0
        while (true) {
            val path = child(dir, if (i == 0) "$stem.$kind" else "$stem-$i.$kind")
            if (space.stat(path) == null) return path
            i++
        }
    }

    companion object {
        /** Side name endings: neutral, as some servers take `.part` files for their own uploads under way. */
        private const val PART = "emanata-new"
        private const val OLD = "emanata-old"
        private const val BUFFER = 1 shl 20

        /** Waits before each try to delete what a failure left. */
        private val DISCARD_WAITS_MS = longArrayOf(0, 1_000, 4_000)

        /** What separates names in [path]: `/` on shares, the device's separator elsewhere. */
        private fun separator(path: String): Char = if (Remotes.isRemote(path)) '/' else File.separatorChar

        /** [name] inside folder [dir]. */
        fun child(dir: String, name: String): String {
            val sep = separator(dir)
            return dir.trimEnd(sep) + sep + name
        }

        /** The folder holding [path]. */
        fun parentOf(path: String): String = path.substringBeforeLast(separator(path), "")

        /** True when [path] is [dir] or inside it. */
        fun isWithin(path: String, dir: String): Boolean {
            val sep = separator(dir)
            return path == dir || path.startsWith(dir.trimEnd(sep) + sep)
        }

        /**
         * A free name beside [name] for "Keep both": `Book (2).cbz`, counting on from a
         * number it already has; a folder's dots are part of its name.
         */
        fun keepBothName(name: String, isDir: Boolean, taken: (String) -> Boolean): String {
            val dot = if (isDir) -1 else name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            val numbered = NUMBERED.matchEntire(stem)
            val base = numbered?.groupValues?.get(1) ?: stem
            var n = (numbered?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
            while (true) {
                val candidate = "$base ($n)$ext"
                if (!taken(candidate)) return candidate
                n++
            }
        }

        private val NUMBERED = Regex("""(.*) \((\d{1,6})\)""")
    }
}
